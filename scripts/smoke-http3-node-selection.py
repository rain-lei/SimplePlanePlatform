"""Smoke the packaged local JAR: startup, bounded failure, live manual switch, unchanged process."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument("--jar", default="proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar")
parser.add_argument("--java", default="java")
args = parser.parse_args()
jar = Path(args.jar).resolve()
if not jar.is_file():
    raise SystemExit(f"Build the local JAR first: {jar}")


def unused_port(kind):
    with socket.socket(socket.AF_INET, kind) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_status(path, predicate):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AssertionError(f"Local process exited: {process.returncode}")
        try:
            value = json.loads(path.read_text(encoding="utf8"))
            if predicate(value):
                return value
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        time.sleep(0.05)
    raise AssertionError("Runtime status did not reach expected state")


def rejected_connection(port):
    started = time.monotonic()
    with socket.create_connection(("127.0.0.1", port), timeout=5) as browser:
        browser.settimeout(5)
        browser.sendall(b"CONNECT smoke.example:443 HTTP/1.1\r\nHost: smoke.example\r\n\r\n")
        response = b""
        while b"\r\n\r\n" not in response:
            block = browser.recv(4096)
            if not block:
                break
            response += block
        assert response.startswith(b"HTTP/1.1 502"), repr(response)
    assert time.monotonic() - started < 5, "Unreachable node did not fail within budget"


with tempfile.TemporaryDirectory(prefix="http3-jar-smoke-") as directory:
    directory = Path(directory)
    config = directory / "proxy.yml"
    status = directory / "proxy.yml.status.json"
    local = unused_port(socket.SOCK_STREAM)
    ports = [unused_port(socket.SOCK_DGRAM), unused_port(socket.SOCK_DGRAM)]
    document = {"localPort": local, "connectionsPerNode": 1, "timeoutMs": 2500,
                "systemProxy": {"enabled": False}, "route": {"defaultRoute": "proxy"},
                "nodeSelection": {"mode": "manual", "selectedNodeId": "a"},
                "remoteServers": [{"id": name, "host": "127.0.0.1", "port": port,
                                   "transport": "http3", "cipher": "none", "http3": {"handshakeTimeoutMs": 750}}
                                  for name, port in zip(["a", "b"], ports)]}
    config.write_text(json.dumps(document), encoding="utf8")  # JSON is a valid YAML subset.
    root = Path(__file__).resolve().parents[1]
    log_path = root / ".build" / "node-selection-jar-smoke.log"
    log_path.parent.mkdir(exist_ok=True)
    with log_path.open("wb") as output:
        process = subprocess.Popen([args.java, "-XX:ActiveProcessorCount=2", "-jar", str(jar), str(config)],
                                   stdout=output, stderr=subprocess.STDOUT, cwd=root)
        try:
            first = wait_status(status, lambda value: value["currentNodeId"] == "a")
            rejected_connection(local)
            wait_status(status, lambda value: value["nodeStatus"] == "unavailable")
            document["nodeSelection"]["selectedNodeId"] = "b"
            temporary = directory / "next.yml"
            temporary.write_text(json.dumps(document), encoding="utf8")
            temporary.replace(config)
            second = wait_status(status, lambda value: value["currentNodeId"] == "b")
            assert first["instanceId"] == second["instanceId"], "Proxy was restarted during switch"
            assert second["savedRevision"] == second["appliedRevision"]
            rejected_connection(local)
            assert process.poll() is None
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    log = log_path.read_text(encoding="utf8", errors="replace")
    assert "Failed to establish HTTP/3 connection" in log, "Packaged HTTP/3 transport did not run"
    assert "No such extension" not in log, "Packaged SPI registration missing"
    print("packaged JAR: passed (startup, HTTP/3 transport, bounded 502, A -> B hot switch, same process)")
