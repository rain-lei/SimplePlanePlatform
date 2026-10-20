#!/usr/bin/env bash
# Deterministic real-service integration; binds only loopback and cleans up its own processes.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
jar=${CRYPTO_JAR:-"$root/proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar"}
mkdir -p "$root/.build/crypto-validation"
run_dir=$(mktemp -d "$root/.build/crypto-validation/run.XXXXXX")
server_pid=""
echo_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
  if [[ -n "$echo_pid" ]]; then kill "$echo_pid" 2>/dev/null || true; wait "$echo_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM
pick_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'; }
wait_port() {
  python3 - "$1" <<'PY'
import socket,sys,time
for attempt in range(100):
    try:
        with socket.create_connection(("127.0.0.1", int(sys.argv[1])), .2): break
    except OSError: time.sleep(.1)
else: raise SystemExit("listener did not become ready")
PY
}
echo_port=$(pick_port)
python3 scripts/crypto/echo-server.py --port "$echo_port" > "$run_dir/echo.log" 2>&1 &
echo_pid=$!
wait_port "$echo_port"
printf '%s' '0123456789abcdef0123456789abcdef' > "$run_dir/key"
printf '%s' '1123456789abcdef0123456789abcdef' > "$run_dir/wrong-key"
cat > "$run_dir/logback.xml" <<'XML'
<configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="C"/></root></configuration>
XML
javac -encoding UTF-8 -source 8 -target 8 -cp "$jar" -d "$run_dir" scripts/crypto/CryptoInteropProbe.java
for suite in ${CRYPTO_TEST_SUITES:-chacha20-poly1305-v2 aes-gcm-v2 chacha20 aes-gcm aes-ctr-hmac}; do
  port=$(pick_port)
  cat > "$run_dir/remote.yml" <<YAML
host: 127.0.0.1
port: $port
transport: http2
cipher: $suite
cipherKey: 0123456789abcdef0123456789abcdef
workerThreads: 2
bossThreads: 1
bizThreads: 4
maxStreams: 100
readIdleTimeout: 120
YAML
  java -XX:ActiveProcessorCount=2 -Xms16m -Xmx128m -Dlogback.configurationFile="$run_dir/logback.xml" \
    -cp "$run_dir:$jar" com.proxy.remote.ProxyRemoteServer > "$run_dir/server-$suite.log" 2>&1 &
  server_pid=$!
  wait_port "$port"
  java -XX:ActiveProcessorCount=2 -Dlogback.configurationFile="$run_dir/logback.xml" -cp "$run_dir:$jar" \
    CryptoInteropProbe 127.0.0.1 "$port" "$suite" "$run_dir/key" 127.0.0.1 "$echo_port" 4 2097152
  if [[ "$suite" == *-v2 ]]; then
    java -XX:ActiveProcessorCount=2 -Dlogback.configurationFile="$run_dir/logback.xml" -cp "$run_dir:$jar" \
      CryptoInteropProbe 127.0.0.1 "$port" "$suite" "$run_dir/wrong-key" 127.0.0.1 "$echo_port" 1 1 expect-reject
    java -XX:ActiveProcessorCount=2 -Dlogback.configurationFile="$run_dir/logback.xml" -cp "$run_dir:$jar" \
      CryptoInteropProbe 127.0.0.1 "$port" chacha20 "$run_dir/key" 127.0.0.1 "$echo_port" 1 1 expect-reject
  fi
  if [[ "${CRYPTO_SKIP_RUST:-0}" != 1 && "$suite" != aes-gcm && "$suite" != aes-ctr-hmac ]]; then
    CRYPTO_TEST_PORT="$port" CRYPTO_TEST_SUITE="$suite" CRYPTO_TEST_KEY_FILE="$run_dir/key" CRYPTO_ECHO_PORT="$echo_port" \
      cargo test --manifest-path plane-core/Cargo.toml --locked --lib crypto_remote_echo -- --ignored --nocapture
  fi
  kill "$server_pid"; wait "$server_pid" || true; server_pid=""
done
printf 'CRYPTO_RUN_LOGS=%s\n' "$run_dir"
