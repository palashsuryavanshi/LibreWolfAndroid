"""Deterministic P2 fixture: media, fullscreen, web app install and share targets.

Covers the Phase 3 items that need a real page:

  /media    a real video element, a play button, a fullscreen button and a
            "share" button that calls navigator.share
  /audio    an audio element, for the media session, audio focus and the
            becoming-noisy / output-device rules
  /app      a page shipping a `display: standalone` manifest with its own icons,
            so the chrome-hiding and shortcut-icon paths can be checked
  /share    the same page reached with a shared text payload, so a share into
            the browser can be told apart from a normal navigation

Usage:  python tools/p2-fixture.py [port] [--tls]

With --tls the same routes are also served on port+1 behind a throwaway
self-signed certificate, so the browser's certificate-error page is reachable
with no internet access at all.

The 20-second MP4 is generated with ffmpeg into %TEMP%\\opencode\\p2media; the
fixture degrades to the audio-only page when it is missing. Nothing is written
inside the repository.
"""

import html
import json
import os
import ssl
import struct
import sys
import tempfile
import threading
import wave
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8799
MEDIA_DIR = os.path.join(tempfile.gettempdir(), "opencode", "p2media")
CLIP = os.path.join(MEDIA_DIR, "clip.mp4")
# A 4:3 copy of the same clip, so the picture-in-picture aspect ratio can be shown
# to come from the video rather than a hard-coded 16:9.
CLIP_43 = os.path.join(MEDIA_DIR, "clip43.mp4")

PAGE_CSS = """
body{font:16px/1.5 system-ui,sans-serif;background:#121014;color:#f4f4f6;
     margin:0;padding:24px}
h1{font-size:20px;margin:0 0 16px}
button{font:16px system-ui,sans-serif;background:#5b4bd6;color:#fff;border:0;
       border-radius:10px;padding:14px 18px;margin:0 10px 10px 0}
a{color:#b9b3ff}
video{width:320px;border-radius:10px;background:#000}
.state{color:#b9b3ff;font-size:14px;margin-top:12px}
"""


def png(width, height, rgba):
    """Minimal solid-colour RGBA PNG, written without any image library."""
    raw = b"".join(b"\x00" + bytes(rgba) * width for _ in range(height))

    def chunk(tag, payload):
        body = tag + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body))

    header = struct.pack(">2I5B", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


ICON_192 = png(192, 192, (91, 75, 214, 255))
ICON_512 = png(512, 512, (91, 75, 214, 255))

TONE = None


def ensure_tone():
    """A two-second 440 Hz WAV, generated on demand."""
    global TONE
    if TONE is not None:
        return TONE
    import io

    buf = io.BytesIO()
    with wave.open(buf, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(8000)
        frames = bytearray()
        for i in range(8000 * 4):
            import math

            value = int(12000 * math.sin(2 * math.pi * 440 * i / 8000))
            frames += struct.pack("<h", value)
        handle.writeframes(bytes(frames))
    TONE = buf.getvalue()
    return TONE


def page(title, body, head=""):
    return (
        "<!doctype html><html><head><meta charset='utf-8'>"
        f"<meta name='viewport' content='width=device-width,initial-scale=1'>"
        f"<title>{title}</title><style>{PAGE_CSS}</style>{head}</head>"
        f"<body><h1>{title}</h1>{body}</body></html>"
    )


MEDIA_BODY = """
<video id="v" src="/clip.mp4" controls playsinline></video>
<p><button id="play">Play</button>
<button id="fs">Fullscreen</button>
<button id="share">navigator.share</button></p>
<p class="state" id="state">idle</p>
<script>
  const v = document.getElementById('v');
  const state = document.getElementById('state');
  const say = (t) => { state.textContent = t; };
  document.getElementById('play').onclick = () => { v.play(); };
  v.addEventListener('play', () => say('playing ' + v.videoWidth + 'x' + v.videoHeight));
  v.addEventListener('pause', () => say('paused'));
  v.addEventListener('enterfullscreen', () => say('fullscreen'));
  v.addEventListener('webkitenterfullscreen', () => say('fullscreen'));
  v.addEventListener('fullscreenchange', () => say('fullscreen ' + !document.fullscreenElement));
  document.getElementById('fs').onclick = () => {
    if (v.requestFullscreen) { v.requestFullscreen(); }
    else if (v.webkitRequestFullscreen) { v.webkitRequestFullscreen(); }
  };
  document.getElementById('share').onclick = () => {
    if (!navigator.share) { say('no navigator.share'); return; }
    navigator.share({ title: 'P2 fixture', text: 'Shared from the P2 fixture' })
      .then(() => say('shared'))
      .catch((e) => say('share failed: ' + e.name));
  };
</script>
"""

AUDIO_BODY = """
<audio id="a" src="/tone.wav" controls></audio>
<p><button id="play">Play audio</button></p>
<p class="state" id="state">idle</p>
<script>
  const a = document.getElementById('a');
  const state = document.getElementById('state');
  document.getElementById('play').onclick = () => { a.play(); };
  a.addEventListener('play', () => state.textContent = 'playing');
  a.addEventListener('pause', () => state.textContent = 'paused');
</script>
"""

APP_BODY = """
<p>This page ships a <code>display: standalone</code> manifest with its own
icons, so the browser should hide its chrome while the page stays on this
origin.</p>
<p><button id="go">Reload the manifest</button></p>
<p class="state" id="state">ready</p>
<script>document.getElementById('go').onclick = () => location.reload();</script>
"""

INDEX_BODY = """
<ul>
  <li><a href="/media">/media</a> &mdash; video, fullscreen, navigator.share</li>
  <li><a href="/audio">/audio</a> &mdash; media session and audio focus</li>
  <li><a href="/app">/app</a> &mdash; standalone manifest, icons, install</li>
</ul>
<p class="state">clip: {clip}</p>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # Request counter, so a device-side tap can be confirmed to have reached the
    # fixture at all before a behaviour is concluded from the UI.
    hits = {}

    def log_message(self, *_args):
        pass

    def reply(self, code, body, ctype="text/html; charset=utf-8", extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for key, value in extra or []:
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        Handler.hits[path] = Handler.hits.get(path, 0) + 1
        if path == "/hits":
            return self.reply(200, json.dumps(Handler.hits), "application/json")
        if path == "/":
            body = INDEX_BODY.format(clip="present" if os.path.exists(CLIP) else "missing")
            return self.reply(200, page("P2 fixture", body))
        if path == "/media":
            if not os.path.exists(CLIP):
                return self.reply(404, page("P2 fixture", "<p>clip.mp4 is missing</p>"))
            return self.reply(200, page("P2 media", MEDIA_BODY))
        if path == "/audio":
            return self.reply(200, page("P2 audio", AUDIO_BODY))
        if path == "/tone.wav":
            return self.reply(200, ensure_tone(), "audio/wav")
        if path == "/clip.mp4":
            if not os.path.exists(CLIP):
                return self.reply(404, "no clip", "text/plain")
            with open(CLIP, "rb") as handle:
                data = handle.read()
            return self.reply(200, data, "video/mp4")
        if path == "/clip43.mp4":
            if not os.path.exists(CLIP_43):
                return self.reply(404, "no clip", "text/plain")
            with open(CLIP_43, "rb") as handle:
                data = handle.read()
            return self.reply(200, data, "video/mp4")
        if path == "/media43":
            body = MEDIA_BODY.replace("/clip.mp4", "/clip43.mp4")
            if not os.path.exists(CLIP_43):
                return self.reply(404, page("P2 fixture", "<p>clip43.mp4 is missing</p>"))
            return self.reply(200, page("P2 media 4:3", body))
        if path == "/pop":
            # One window per tap. /tabs fires three window.open calls a few
            # hundred milliseconds apart, which collide with a single modal
            # prompt, so it cannot show which of them took focus. This can.
            body = """
<button id="open" style="font-size:18px;padding:12px 20px">Open one window</button>
<p class="state" id="state">not opened</p>
<script>
  document.getElementById('open').addEventListener('click', function () {
    document.getElementById('state').textContent = 'asked';
    window.open('/ua', '_blank');
  });
</script>
"""
            return self.reply(200, page("P2 one window", body))
        if path == "/tabs":
            body = """
<p>Opens three background tabs, for the memory-pressure and tab-suspension
checks.</p>
<p class="state" id="state">opening…</p>
<script>
  ['/media', '/app', '/audio'].forEach(function (u, i) {
    setTimeout(function () { window.open(u, '_blank'); }, 400 * (i + 1));
  });
  setTimeout(function () {
    document.getElementById('state').textContent = 'opened';
  }, 2200);
</script>
"""
            return self.reply(200, page("P2 tabs", body))
        if path == "/app":
            head = "<link rel='manifest' href='/manifest.webmanifest'>"
            return self.reply(200, page("P2 web app", APP_BODY, head))
        if path == "/manifest.webmanifest":
            manifest = {
                "name": "P2 fixture web app",
                "short_name": "P2 app",
                "start_url": "/app",
                "scope": "/",
                "display": "standalone",
                "background_color": "#121014",
                "theme_color": "#5b4bd6",
                "icons": [
                    {
                        "src": "/icon-192.png",
                        "sizes": "192x192",
                        "type": "image/png",
                        "purpose": "any",
                    },
                    {
                        "src": "/icon-512.png",
                        "sizes": "512x512",
                        "type": "image/png",
                        "purpose": "maskable",
                    },
                ],
            }
            return self.reply(200, json.dumps(manifest), "application/manifest+json")
        if path == "/icon-192.png":
            return self.reply(200, ICON_192, "image/png")
        if path == "/favicon.ico":
            # The shell's favicon loader asks for this on the origin root.
            return self.reply(200, ICON_192, "image/png")
        if path == "/ua":
            # Reads the User-Agent the engine actually put on the wire, rather
            # than what a page's script believes, so the desktop agent override
            # is checked against the request and not against the DOM.
            agent = self.headers.get("User-Agent", "(none)")
            body = (
                "<p>User agent this request carried:</p>"
                f"<p class=\"ua\"><code>{html.escape(agent)}</code></p>"
                "<p>Identified as:</p><p class=\"mobile\">"
                f"<code>{'mobile' if 'Mobile' in agent else 'desktop'}</code></p>"
            )
            return self.reply(200, page("User agent", body))
        if path == "/risky":
            body = """
<style>.dl{display:block;padding:18px 20px;margin:10px 0;background:#221f2e;
border-radius:12px;font-size:18px}</style>
<p>Links for the download-safety check. The first three are served with
installer or document content types; the last one only looks like a document by
its first extension. None of them is a real program.</p>
<a class="dl" href="/payload.apk">payload.apk</a>
<a class="dl" href="/payload.exe">payload.exe</a>
<a class="dl" href="/payload.pdf">payload.pdf</a>
<a class="dl" href="/invoice.pdf.exe">invoice.pdf.exe</a>
"""
            return self.reply(200, page("Risky downloads", body))
        if path in ("/payload.apk", "/payload.exe", "/payload.pdf", "/invoice.pdf.exe"):
            names = {
                "/payload.apk": ("payload.apk", "application/vnd.android.package-archive"),
                "/payload.exe": ("payload.exe", "application/x-msdownload"),
                "/payload.pdf": ("payload.pdf", "application/pdf"),
                "/invoice.pdf.exe": ("invoice.pdf.exe", "application/pdf"),
            }
            name, ctype = names[path]
            return self.reply(
                200,
                b"P2 fixture payload, not a real program.\n",
                ctype,
                extra=[("Content-Disposition", f'attachment; filename="{name}"')],
            )
        if path == "/icon-512.png":
            return self.reply(200, ICON_512, "image/png")
        return self.reply(404, page("P2 fixture", "<p>not found</p>"))


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    if len(sys.argv) > 2 and sys.argv[2] == "--tls":
        # A second listener on the same routes behind a throwaway self-signed
        # certificate, so the certificate-error page is reachable without any
        # internet access. The certificate is generated into the temp directory
        # and is deliberately not trusted by the device.
        tls_port = PORT + 1
        cert_dir = os.path.join(tempfile.gettempdir(), "opencode", "p2tls")
        os.makedirs(cert_dir, exist_ok=True)
        cert = os.path.join(cert_dir, "cert.pem")
        key = os.path.join(cert_dir, "key.pem")
        if not os.path.exists(cert):
            import subprocess

            subprocess.run(
                [
                    "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                    "-keyout", key, "-out", cert, "-days", "30",
                    "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
                ],
                check=True,
                capture_output=True,
            )
        tls_server = ThreadingHTTPServer(("127.0.0.1", tls_port), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        tls_server.socket = context.wrap_socket(tls_server.socket, server_side=True)
        threading.Thread(target=tls_server.serve_forever, daemon=True).start()
        print(
            f"p2-fixture also on https://127.0.0.1:{tls_port} with an untrusted certificate",
            flush=True,
        )
    print(f"p2-fixture on http://127.0.0.1:{PORT} (media dir {MEDIA_DIR})", flush=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        thread.join()
    except KeyboardInterrupt:
        server.shutdown()


if __name__ == "__main__":
    main()
