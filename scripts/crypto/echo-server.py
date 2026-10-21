"""Loopback-only echo target for encryption integration tests."""
import argparse
import socketserver

class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            data = self.request.recv(32768)
            if not data:
                return
            self.request.sendall(data)

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=19101)
    args = parser.parse_args()
    with Server(("127.0.0.1", args.port), Echo) as server:
        server.serve_forever()
