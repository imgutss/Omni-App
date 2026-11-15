#!/usr/bin/env python3
"""Local stand-in for the Google Apps Script door.

It behaves like the deployed script: one JSON body in, the exit node's JSON body out. It exists to
prove the guts protocol end to end without Google, and it is what the smoke test uses.
"""
import json
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    exit_url = "http://127.0.0.1:8087/g"
    key = "testkey"

    def do_POST(self):  # noqa: N802 - http.server API
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        try:
            req = json.loads(body)
        except json.JSONDecodeError as exc:
            self._send({"ok": False, "e": f"json: {exc}"})
            return
        if req.get("k") != Handler.key:
            self._send({"ok": False, "e": "unauthorized"})
            return
        forward = urllib.request.Request(
            Handler.exit_url,
            data=json.dumps(req).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(forward, timeout=60) as resp:
                payload = resp.read()
        except Exception as exc:  # pragma: no cover - failure path
            self._send({"ok": False, "e": f"relay: {exc}"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _send(self, obj):
        payload = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
    if len(sys.argv) > 2:
        Handler.exit_url = sys.argv[2]
    if len(sys.argv) > 3:
        Handler.key = sys.argv[3]
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"door emulator on 127.0.0.1:{port} -> {Handler.exit_url}", flush=True)
    server.serve_forever()
