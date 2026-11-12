#!/usr/bin/env python3
"""Serve the CrackedAuth web interface on a port.

Standard library only, so it runs anywhere Python 3 is available:

    python3 web/serve.py            # http://0.0.0.0:8000
    PORT=3000 python3 web/serve.py  # http://0.0.0.0:3000

Routes:
    /                  the interface (web/index.html)
    /CrackedAuth.jar   the newest build from dist/, as a plain jar download
    /healthz           "ok" for container and tunnel health checks
"""

from __future__ import annotations

import os
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
DIST = HERE.parent / "dist"


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(HERE), **kwargs)

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path in ("/healthz", "/health"):
            self._send_bytes(b"ok\n", "text/plain; charset=utf-8")
            return
        if path == "/CrackedAuth.jar":
            self._send_jar("CrackedAuth")
            return
        super().do_GET()

    def _send_bytes(self, body: bytes, content_type: str, extra_headers: dict[str, str] | None = None) -> None:
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _send_jar(self, name: str) -> None:
        jars = sorted(DIST.glob(f"{name}-*.jar"))
        if not jars:
            self.send_error(404, "No build found. Run ./gradlew build first.")
            return
        jar = jars[-1]
        self._send_bytes(
            jar.read_bytes(),
            "application/java-archive",
            {"Content-Disposition": f'attachment; filename="{jar.name}"'},
        )

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main() -> None:
    port = int(os.environ.get("PORT", "8000"))
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"CrackedAuth web interface on http://0.0.0.0:{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
