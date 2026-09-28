"""Tiny static server with HTTP Range support, for download pause/resume testing.

Serves:
  /            -> test page with a big downloadable file
  /big.bin     -> 256 MB deterministic payload, honours Range requests

Usage: python tools/download-test-server.py [port] [size_mb] [throttle_mb_per_s]
"""
import http.server
import os
import socketserver
import sys
import tempfile
import threading
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8766
SIZE_MB = int(sys.argv[2]) if len(sys.argv) > 2 else 256
THROTTLE_MB = float(sys.argv[3]) if len(sys.argv) > 3 else 0  # 0 = unlimited
CHUNK = b"LW-DOWNLOAD-TEST-" * 4096  # 72 KB
TOTAL = SIZE_MB * 1024 * 1024
PAYLOAD_DIR = os.environ.get(
    "LW_PAYLOAD_DIR",
    os.path.join(tempfile.gettempdir(), "librewolf-download-test"),
)

PAGE = f"""<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Download test</title></head>
<body style="font-family:sans-serif;background:#fff;color:#000;padding:24px">
<h2>Download test server</h2>
<p>Payload: {SIZE_MB} MB (Range supported)</p>
<p><a id="dl" href="/big.bin" download="lw-test-{SIZE_MB}mb.bin"
   style="font-size:18px;padding:12px 20px;background:#9B8CFF;color:#000;
          border-radius:12px;text-decoration:none;display:inline-block">
   Download big.bin</a></p>
<p><a href="/big.bin" id="view">Open big.bin in a tab (Content-Disposition inline)</a></p>
</body></html>"""


def write_payload(path):
    if os.path.exists(path) and os.path.getsize(path) == TOTAL:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        written = 0
        while written < TOTAL:
            n = min(len(CHUNK), TOTAL - written)
            fh.write(CHUNK[:n])
            written += n


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write("%s - %s\n" % (self.address_string(), fmt % args))
        sys.stdout.flush()

    def _payload(self):
        return os.path.join(PAYLOAD_DIR, "payload.bin")

    def do_HEAD(self):
        self.do_GET(head_only=True)

    def do_GET(self, head_only=False):
        if self.path.startswith("/big.bin"):
            path = self._payload()
            if not os.path.exists(path):
                write_payload(path)
            size = TOTAL
            start, end = 0, size - 1
            status = 200
            rng = self.headers.get("Range")
            if rng and rng.startswith("bytes="):
                spec = rng.split("=", 1)[1].split(",")[0]
                try:
                    if spec.startswith("-"):
                        start = max(0, size - int(spec[1:]))
                    else:
                        start = int(spec.split("-")[0])
                    if start >= size:
                        self.send_response(416)
                        self.send_header("Content-Range", "bytes */%d" % size)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    status = 206
                except ValueError:
                    start = 0
            length = end - start + 1
            self.send_response(status)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(length))
            if status == 206:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            self.send_header(
                "Content-Disposition",
                'attachment; filename="lw-test-%dmb.bin"' % SIZE_MB,
            )
            self.end_headers()
            if head_only:
                return
            with open(path, "rb") as fh:
                fh.seek(start)
                remaining = length
                started = time.time()
                sent = 0
                while remaining > 0:
                    data = fh.read(min(256 * 1024, remaining))
                    if not data:
                        break
                    try:
                        self.wfile.write(data)
                    except (BrokenPipeError, ConnectionResetError):
                        self.log_message(
                            "client stopped at offset %d (%d bytes sent)",
                            start + sent, sent,
                        )
                        return
                    sent += len(data)
                    remaining -= len(data)
                    if THROTTLE_MB > 0:
                        target = sent / (THROTTLE_MB * 1024 * 1024)
                        drift = target - (time.time() - started)
                        if drift > 0:
                            time.sleep(drift)
            self.log_message(
                "served status=%d range=%s bytes=%d complete",
                status, self.headers.get("Range") or "-", sent,
            )
            return

        body = PAGE.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head_only:
            self.wfile.write(body)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    os.makedirs(PAYLOAD_DIR, exist_ok=True)
    path = os.path.join(PAYLOAD_DIR, "payload.bin")
    print("Preparing %d MB payload in %s..." % (SIZE_MB, PAYLOAD_DIR))
    write_payload(path)
    print("Serving on http://127.0.0.1:%d (throttle=%s MB/s)" % (PORT, THROTTLE_MB or "none"))
    with Server(("127.0.0.1", PORT), Handler) as httpd:
        httpd.serve_forever()
