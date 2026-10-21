#!/usr/bin/env bash
# Verifies the application cipher against a loopback-only remote fixture through SSH.
# Usage: bash scripts/crypto/test-remote-over-ssh.sh host private-key known-hosts suite
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
host=$1
private_key=$2
known_hosts=$3
suite=$4
run_dir="$root/.build/crypto-validation/ssh-$suite"
mkdir -p "$run_dir"
temp_key=$(mktemp /tmp/simpleplane-crypto-key.XXXXXX)
tunnel_pid=""
cleanup() {
  if [[ -n "$tunnel_pid" ]]; then kill "$tunnel_pid" 2>/dev/null || true; wait "$tunnel_pid" 2>/dev/null || true; fi
  rm -f -- "$temp_key"
}
trap cleanup EXIT INT TERM
cat "$private_key" > "$temp_key"
chmod 600 "$temp_key"
ssh -i "$temp_key" -o BatchMode=yes -o ConnectTimeout=10 -o ExitOnForwardFailure=yes \
  -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$known_hosts" \
  -N -L 127.0.0.1:29111:127.0.0.1:19111 "ec2-user@$host" > "$run_dir/tunnel.log" 2>&1 &
tunnel_pid=$!
python3 - <<'PY'
import socket,time
for _ in range(150):
    try:
        with socket.create_connection(("127.0.0.1",29111),.2): break
    except OSError: time.sleep(.1)
else: raise SystemExit("SSH tunnel did not become ready; inspect tunnel.log")
PY
printf '%s' '0123456789abcdef0123456789abcdef' > "$run_dir/key"
jar="$root/proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar"
javac -encoding UTF-8 -cp "$jar" -d "$run_dir" scripts/crypto/CryptoInteropProbe.java
cat > "$run_dir/logback.xml" <<'XML'
<configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="C"/></root></configuration>
XML
if [[ "${CRYPTO_SKIP_JAVA:-0}" != 1 ]]; then
  timeout 210 java -XX:ActiveProcessorCount=2 -Dcrypto.probe.chunkBytes=32769 -Dlogback.configurationFile="$run_dir/logback.xml" -cp "$run_dir:$jar" \
    CryptoInteropProbe 127.0.0.1 29111 "$suite" "$run_dir/key" 127.0.0.1 19101 4 262144
fi
CRYPTO_TEST_PORT=29111 CRYPTO_TEST_SUITE="$suite" CRYPTO_TEST_KEY_FILE="$run_dir/key" CRYPTO_ECHO_PORT=19101 \
  CRYPTO_TEST_ROUNDS=8 CRYPTO_TEST_PAYLOAD=32769 CRYPTO_TEST_TIMEOUT_SECONDS=120 \
  cargo test --manifest-path plane-core/Cargo.toml --locked --lib crypto_remote_echo -- --ignored --nocapture
