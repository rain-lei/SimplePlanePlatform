#!/usr/bin/env bash
# Run from an isolated copy of the test project. Does not touch the deployed service.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
run_dir="$root/.build/crypto-validation/fixture"
mkdir -p "$run_dir"
action=${1:-start}
if [[ "$action" == stop ]]; then
  for kind in server echo; do
    if [[ -f "$run_dir/$kind.pid" ]]; then
      pid=$(cat "$run_dir/$kind.pid")
      if [[ -r "/proc/$pid/cmdline" ]] && tr '\0' ' ' < "/proc/$pid/cmdline" | grep -Fq "$root"; then
        kill "$pid"
      fi
    fi
  done
  exit 0
fi
suite=${2:-chacha20-poly1305-v2}
if [[ "$suite" != chacha20-poly1305-v2 && "$suite" != aes-gcm-v2 ]]; then
  printf 'Unsupported test suite\n' >&2; exit 1
fi
for kind in server echo; do
  if [[ -f "$run_dir/$kind.pid" ]] && kill -0 "$(cat "$run_dir/$kind.pid")" 2>/dev/null; then
    printf 'Fixture still running; stop it before starting another\n' >&2; exit 1
  fi
done
printf '%s' '0123456789abcdef0123456789abcdef' > "$run_dir/key"
cat > "$run_dir/remote.yml" <<YAML
host: 127.0.0.1
port: 19111
transport: http2
cipher: $suite
cipherKey: 0123456789abcdef0123456789abcdef
workerThreads: 2
bossThreads: 1
bizThreads: 4
maxStreams: 100
readIdleTimeout: 120
YAML
cat > "$run_dir/logback.xml" <<'XML'
<configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="C"/></root></configuration>
XML
nohup python3 "$root/scripts/crypto/echo-server.py" --port 19101 < /dev/null > "$run_dir/echo.log" 2>&1 &
echo $! > "$run_dir/echo.pid"
nohup java -XX:ActiveProcessorCount=2 -Xms16m -Xmx96m -Dlogback.configurationFile="$run_dir/logback.xml" \
  -cp "$run_dir:${CRYPTO_JAR:-$root/proxy-remote/target/proxy-remote-1.0.0-SNAPSHOT.jar}" com.proxy.remote.ProxyRemoteServer \
  < /dev/null > "$run_dir/server.log" 2>&1 &
echo $! > "$run_dir/server.pid"
sleep 1
kill -0 "$(cat "$run_dir/server.pid")"
printf 'FIXTURE_STARTED suite=%s port=19111 echo=19101\n' "$suite"
