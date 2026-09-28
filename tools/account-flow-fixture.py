"""Credential-free fixture for account-style browser flows.

Real account testing (Gmail, Drive, WhatsApp Web, a bank) needs credentials this
project does not have and must not store. Everything those flows exercise on the
*browser* side, however, is deterministic and can be tested without an account:

  * cookie session set/read/cleared across redirects
  * a login wall: protected page bounces to /login, then returns after POST
  * OAuth-style popup: window.open + postMessage back to the opener
  * HTTP Basic authentication prompt and retry with credentials
  * multipart file upload through the Android document picker
  * an authenticated (cookie + header) download, exercising the download engine
  * a redirect chain, a repost warning, and an external-protocol link
  * PWA manifest + service worker (install flow)
  * a share-target POST endpoint
  * a deliberately failing content process (crash recovery)

Run:
    python tools/account-flow-fixture.py [port]

Then, with the phone attached over wireless adb:
    adb reverse tcp:8777 tcp:8777
    adb shell am start -a android.intent.action.VIEW -d http://127.0.0.1:8777/
"""

import base64
import hashlib
import http.cookies
import http.server
import json
import os
import re
import socketserver
import sys
import tempfile
import threading
import time
import urllib.parse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8777
SESSION_COOKIE = "lw_fixture_session"
VALID_USER = "tester"
VALID_PASS = "correct-horse"
VALID_BASIC = base64.b64encode(f"{VALID_USER}:{VALID_PASS}".encode()).decode()

PAYLOAD_DIR = os.environ.get(
    "LW_PAYLOAD_DIR", os.path.join(tempfile.gettempdir(), "librewolf-account-fixture")
)
REPORT_PATH = os.environ.get(
    "LW_REPORT", os.path.join(PAYLOAD_DIR, "submissions.log")
)

PAGE_CSS = """
body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;
     background:#121014;color:#f4f1f7;padding:20px;line-height:1.5}
h1{font-size:22px;margin:0 0 4px}
h2{font-size:15px;color:#b9a7ff;margin:22px 0 8px}
p.sub{color:#a79fb0;margin:0 0 18px;font-size:13px}
a.card,button{display:block;width:100%;box-sizing:border-box;text-align:left;
  background:#1e1a22;color:#f4f1f7;border:1px solid #332c3d;border-radius:12px;
  padding:13px 15px;margin:7px 0;font-size:15px;text-decoration:none;cursor:pointer}
a.card:active,button:active{background:#2a2432}
.tag{display:inline-block;font-size:11px;color:#121014;background:#b9a7ff;
  border-radius:6px;padding:2px 7px;margin-left:8px;vertical-align:middle}
.ok{color:#7ee787}.bad{color:#ff7b72}
input{width:100%;box-sizing:border-border-box;padding:12px;margin:7px 0;
  border-radius:10px;border:1px solid #332c3d;background:#1e1a22;color:#f4f1f7;font-size:15px}
code{background:#1e1a22;padding:2px 6px;border-radius:6px;font-size:13px}
"""


def page(title, body, sub="Local fixture - no real account, no real credentials"):
    return f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title}</title><style>{PAGE_CSS}</style></head>
<body><h1>{title}</h1><p class="sub">{sub}</p>{body}</body></html>""".encode()


def nav(links):
    out = []
    for href, label, tag in links:
        badge = f'<span class="tag">{tag}</span>' if tag else ""
        out.append(f'<a class="card" href="{href}">{label}{badge}</a>')
    return "".join(out)


HOME_LINKS = [
    ("/login", "Login wall (form POST + cookie session)", "cookie"),
    ("/protected", "Protected page (redirects to login when signed out)", "redirect"),
    ("/basic", "HTTP Basic authentication", "auth"),
    ("/oauth/start", "OAuth-style popup (window.open + postMessage)", "popup"),
    ("/upload", "File upload (Android document picker)", "upload"),
    ("/download-protected", "Authenticated download (cookie required)", "download"),
    ("/repost", "POST + reload (repost warning)", "repost"),
    ("/external", "External protocol link (intent routing)", "intent"),
    ("/pwa", "Installable PWA (manifest + service worker)", "pwa"),
    ("/share-target", "Share target POST endpoint", "share"),
    ("/crash", "Kill the content process (crash recovery)", "crash"),
    ("/slow", "Slow streaming response (media / timeouts)", "slow"),
    ("/errors/dns", "Error pages: DNS / refused / TLS / HTTP", "errors"),
]

HOME = page(
    "Account-flow fixture",
    "<h2>Flows</h2>" + nav(HOME_LINKS)
    + '<h2>Expected credentials</h2>'
      '<p class="sub">Login form: <code>tester</code> / <code>correct-horse</code>. '
      'Basic auth: same pair.</p>',
)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LibreWolfAccountFixture/1.0"

    # ---- helpers ----

    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s\n" % (self.log_message_tag, fmt % args))
        sys.stdout.flush()

    @property
    def log_message_tag(self):
        return "%s:%s" % self.client_address[:2]

    def reply(self, code, body=b"", ctype="text/html; charset=utf-8", extra=None):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or []):
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def redirect(self, location, code=302, cookie=None):
        extra = [("Location", location)]
        if cookie:
            extra.append(("Set-Cookie", cookie))
        self.send_response(code)
        for k, v in extra:
            self.send_header(k, v)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def session(self):
        raw = self.headers.get("Cookie", "")
        jar = http.cookies.SimpleCookie()
        try:
            jar.load(raw)
        except Exception:
            return None
        morsel = jar.get(SESSION_COOKIE)
        return morsel.value if morsel else None

    def signed_in(self):
        return self.session() == hashlib.sha256(
            (VALID_USER + VALID_PASS).encode()
        ).hexdigest()[:32]

    def read_body(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        return self.rfile.read(length) if length else b""

    def banner(self, signed_in, extra=""):
        state = (
            '<span class="ok">signed in</span>'
            if signed_in
            else '<span class="bad">signed out</span>'
        )
        return (
            f'<p>Session: {state} '
            f'<a href="/logout" style="color:#b9a7ff">sign out</a></p>{extra}'
        )

    # ---- routing ----

    def do_HEAD(self):
        self._head_only = True
        try:
            self.do_GET()
        finally:
            self._head_only = False

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/login":
            return self.handle_login()
        if path == "/oauth/callback":
            return self.handle_oauth_callback()
        if path == "/share-target":
            return self.handle_share_target()
        if path == "/upload":
            return self.handle_upload()
        if path == "/repost":
            body = self.read_body().decode("utf-8", "replace")
            self.record("repost", {"body": body})
            return self.reply(
                200,
                page(
                    "POST received",
                    '<div class="card" style="cursor:default">The POST body arrived: '
                    f"<code>{html_escape(body)}</code></div>"
                    '<p><a class="card" href="/repost">Back to the form</a></p>'
                    '<p class="sub">Reload now: the browser should warn before '
                    "resending the POST.</p>",
                ),
            )
        return self.reply(404, page("Not found", "<p>No such POST target.</p>"))

    def do_GET(self):
        head_only = getattr(self, "_head_only", False)
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path == "/":
            return self.reply(200, HOME)
        if path == "/notify":
            unique = urllib.parse.parse_qs(parsed.query).get("n", ["1"])[0]
            body = f"""
            <p>Posts a web notification so the browser's notification icon can be
            checked in the shade.</p>
            <button id="go">Post a notification</button>
            <p id="state" class="small">Waiting</p>
            <script>
              document.getElementById('go').onclick = function () {{
                Notification.requestPermission().then(function (p) {{
                  if (p !== 'granted') {{
                    document.getElementById('state').textContent = 'denied: ' + p;
                    return;
                  }}
                  new Notification('LibreWolf fixture {unique}', {{
                    body: 'Web notification from the account-flow fixture.',
                    tag: 'lw-fixture-{unique}'
                  }});
                  document.getElementById('state').textContent = 'posted {unique}';
                }});
              }};
            </script>"""
            return self.reply(200, page("Notification", body))
        if path == "/login":
            return self.handle_login_form()
        if path == "/logout":
            return self.redirect(
                "/", cookie=f"{SESSION_COOKIE}=; Max-Age=0; Path=/"
            )
        if path == "/protected":
            return self.handle_protected()
        if path == "/session":
            return self.handle_session()
        if path == "/basic":
            return self.handle_basic()
        if path == "/oauth/start":
            return self.handle_oauth_start()
        if path == "/oauth/popup":
            return self.handle_oauth_popup()
        if path == "/upload":
            return self.handle_upload_form()
        if path == "/download-protected":
            return self.handle_protected_download()
        if path == "/repost":
            return self.handle_repost()
        if path == "/external":
            return self.handle_external()
        if path == "/pwa":
            return self.handle_pwa()
        if path == "/manifest.webmanifest":
            return self.handle_manifest()
        if path == "/sw.js":
            return self.handle_sw()
        if path == "/crash":
            return self.handle_crash()
        if path == "/slow":
            return self.handle_slow(head_only)
        if path.startswith("/errors/"):
            return self.handle_errors(path, head_only)
        return self.reply(404, page("Not found", '<p>No such page.</p>'))

    # ---- flows ----

    def handle_login_form(self):
        if self.signed_in():
            return self.reply(
                200,
                page(
                    "Login",
                    self.banner(True)
                    + '<p><a class="card" href="/protected">Go to protected page</a></p>',
                ),
            )
        body = f"""
        {self.banner(False)}
        <form method="POST" action="/login">
          <input name="user" placeholder="Username" autocomplete="username">
          <input name="pass" type="password" placeholder="Password"
                 autocomplete="current-password">
          <button type="submit">Sign in</button>
        </form>
        <p class="sub">POSTs to /login, sets a session cookie, redirects to
        <code>/protected</code>.</p>"""
        return self.reply(200, page("Login", body))

    def handle_login(self):
        form = urllib.parse.parse_qs(self.read_body().decode("utf-8", "replace"))
        user = (form.get("user") or [""])[0]
        pw = (form.get("pass") or [""])[0]
        if user == VALID_USER and pw == VALID_PASS:
            token = hashlib.sha256((VALID_USER + VALID_PASS).encode()).hexdigest()[:32]
            cookie = f"{SESSION_COOKIE}={token}; Path=/; HttpOnly; SameSite=Lax"
            return self.redirect("/protected", cookie=cookie)
        return self.reply(
            401,
            page(
                "Login failed",
                '<p class="bad">Incorrect username or password.</p>'
                '<p><a class="card" href="/login">Try again</a></p>',
            ),
        )

    def handle_protected(self):
        if not self.signed_in():
            # Classic login wall: bounce, then return here after sign-in.
            return self.redirect("/login?next=/protected")
        body = self.banner(
            True,
            '<div class="card" style="cursor:default">Protected content unlocked. '
            "The cookie survived the redirect chain.</div>",
        ) + nav(
            [
                ("/download-protected", "Download a file that needs this cookie", "download"),
                ("/session", "Inspect session cookies", "cookie"),
            ]
        )
        return self.reply(200, page("Protected page", body))

    def handle_session(self):
        raw = self.headers.get("Cookie", "(none)")
        rows = "".join(
            f"<tr><td>{c.strip()}</td></tr>" for c in raw.split(";") if c.strip()
        ) or "<tr><td>(no cookies sent)</td></tr>"
        body = self.banner(self.signed_in()) + (
            f"<h2>Cookie header received</h2><code>{raw}</code>"
            f"<table>{rows}</table>"
        )
        return self.reply(200, page("Session inspector", body))

    def handle_basic(self):
        auth = self.headers.get("Authorization", "")
        if auth == "Basic " + VALID_BASIC:
            body = (
                '<div class="card" style="cursor:default">HTTP Basic accepted.</div>'
                + nav([("/protected", "Cookie-protected page", "cookie")])
            )
            return self.reply(200, page("Basic auth", body))
        body = (
            "<p>This page requires a username and password. The browser should show "
            "its own prompt; enter the fixture credentials.</p>"
        )
        return self.reply(
            401,
            page("Basic auth required", body),
            extra=[("WWW-Authenticate", 'Basic realm="LibreWolf Fixture"')],
        )

    def handle_oauth_start(self):
        body = """
        <p>This opens a popup window, completes a redirect inside it, then posts the
        result back to this page - the shape of every OAuth consent flow.</p>
        <button onclick="startOAuth()">Start OAuth popup</button>
        <p id="result" class="sub">Waiting...</p>
        <script>
        function startOAuth(){
          var w = window.open('/oauth/popup', 'lw-oauth', 'width=420,height=560');
          if(!w){ document.getElementById('result').innerHTML =
                  '<span class="bad">Popup blocked</span>'; }
        }
        window.addEventListener('message', function(e){
          if(e.data && e.data.type==='lw-oauth-done'){
            document.getElementById('result').innerHTML =
              '<span class="ok">Popup returned: ' + e.data.code + '</span>';
            window.close();
          }
        });
        </script>"""
        return self.reply(200, page("OAuth popup", body))

    def handle_oauth_popup(self):
        body = """
        <p>Authorising the fixture application...</p>
        <script>
          var code = 'fixture-code-' + Date.now();
          setTimeout(function(){
            if(window.opener){
              window.opener.postMessage({type:'lw-oauth-done', code:code}, '*');
              document.body.innerHTML = '<p class="ok">Token returned. You can close '
                + 'this window.</p>';
            } else {
              document.body.innerHTML = '<p class="bad">No opener: popup did not '
                + 'come from a page.</p>';
            }
          }, 600);
        </script>"""
        return self.reply(200, page("Authorising...", body))

    def handle_oauth_callback(self):
        self.redirect("/")

    def handle_share_target(self):
        raw = self.read_body().decode("utf-8", "replace")
        self.record("share-target", {"body": raw[:2000]})
        body = (
            '<div class="card" style="cursor:default">Share target received '
            f"<code>{html_escape(raw[:400])}</code></div>"
            '<p><a class="card" href="/">Back to the index</a></p>'
        )
        return self.reply(200, page("Share target", body))

    def handle_upload_form(self):
        body = """
        <p>Submitting a file exercises the Android document picker and the
        multipart upload path.</p>
        <form method="POST" action="/upload" enctype="multipart/form-data">
          <input type="file" name="doc">
          <input name="note" placeholder="Note">
          <button type="submit">Upload</button>
        </form>"""
        return self.reply(200, page("File upload", body))

    def handle_upload(self):
        raw = self.read_body()
        ctype = self.headers.get("Content-Type", "")
        name, size, fields = "(unknown)", 0, {}
        if "multipart/form-data" in ctype:
            name, size, fields = parse_multipart(raw, ctype)
        self.record("upload", {"name": name, "size": size, "fields": fields})
        body = (
            f'<div class="card" style="cursor:default">Received '
            f"<b>{name}</b> ({size} bytes). Fields: "
            f"<code>{html_escape(json.dumps(fields))}</code></div>"
            '<p><a class="card" href="/upload">Upload another</a></p>'
        )
        return self.reply(200, page("Upload received", body))

    def handle_protected_download(self):
        if not self.signed_in():
            return self.redirect("/login?next=/download-protected")
        path = os.path.join(PAYLOAD_DIR, "protected.bin")
        total = int(os.environ.get("LW_PROTECTED_BYTES", 8 * 1024 * 1024))
        if not (os.path.exists(path) and os.path.getsize(path) == total):
            os.makedirs(PAYLOAD_DIR, exist_ok=True)
            block = b"LW-FIXTURE-" * 4096
            with open(path, "wb") as fh:
                written = 0
                while written < total:
                    n = min(len(block), total - written)
                    fh.write(block[:n])
                    written += n
        size = os.path.getsize(path)
        start, status = 0, 200
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            try:
                start = int(rng.split("=")[1].split("-")[0])
                status = 206
            except ValueError:
                start = 0
        if start >= size:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        length = size - start
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        self.send_header(
            "Content-Disposition", 'attachment; filename="fixture-protected.bin"'
        )
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{size - 1}/{size}")
        self.end_headers()
        with open(path, "rb") as fh:
            fh.seek(start)
            remaining = length
            while remaining > 0:
                chunk = fh.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)

    def handle_repost(self):
        body = """
        <p>Submit this form, then press reload. The browser should warn before
        repeating the POST.</p>
        <form method="POST" action="/repost">
          <input name="value" placeholder="Anything">
          <button type="submit">POST it</button>
        </form>"""
        return self.reply(200, page("Repost warning", body))

    def handle_external(self):
        body = (
            '<p>These hand off to other Android apps. The browser should offer to '
            "open them rather than failing.</p>"
            + nav(
                [
                    ("tel:+15550100", "tel: URI", "intent"),
                    ("mailto:tester@example.invalid", "mailto: URI", "intent"),
                    ("market://details?id=com.palash.librewolfandroid", "market: URI", "intent"),
                ]
            )
        )
        return self.reply(200, page("External protocols", body))

    def handle_pwa(self):
        body = """
        <p>This page ships a manifest and a service worker, so the browser can
        offer "Install app" / add a home-screen shortcut.</p>
        <p><a class="card" href="/manifest.webmanifest">manifest.webmanifest</a></p>
        <p id="sw" class="sub">Service worker: registering...</p>
        <script>
          if('serviceWorker' in navigator){
            navigator.serviceWorker.register('/sw.js')
              .then(function(){ document.getElementById('sw').innerHTML =
                                '<span class="ok">Service worker registered.</span>'; })
              .catch(function(e){ document.getElementById('sw').innerHTML =
                                '<span class="bad">' + e + '</span>'; });
          } else {
            document.getElementById('sw').innerHTML =
              '<span class="bad">Service workers unsupported</span>';
          }
        </script>"""
        return self.reply(200, page("Installable PWA", body))

    def handle_manifest(self):
        manifest = {
            "name": "LibreWolf Account Fixture",
            "short_name": "LW Fixture",
            "start_url": "/pwa",
            "scope": "/",
            "display": "standalone",
            "background_color": "#121014",
            "theme_color": "#121014",
            "icons": [],
        }
        return self.reply(
            200,
            json.dumps(manifest),
            ctype="application/manifest+json",
        )

    def handle_sw(self):
        js = (
            "self.addEventListener('install', function(e){"
            "self.skipWaiting();});"
            "self.addEventListener('activate', function(e){"
            "e.waitUntil(self.clients.claim());});"
        )
        return self.reply(
            200,
            js,
            ctype="application/javascript",
            extra=[("Service-Worker-Allowed", "/")],
        )

    def handle_crash(self):
        body = """
        <p>Pressing the button below kills the Gecko content process. The tab
        should come back as a crashed tab with a recovery option, and the rest of
        the browser must stay alive.</p>
        <button onclick="crashIt()">Kill the content process</button>
        <script>
          function crashIt(){
            setTimeout(function(){
              try {
                // Null dereference inside the content process.
                var x = null; x.boom();
              } catch (e) {}
              // Fallback that reliably takes the whole process down.
              while (true) {}
            }, 50);
          }
        </script>"""
        return self.reply(200, page("Content-process crash", body))

    def handle_slow(self, head_only):
        total = 24 * 1024 * 1024
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(total))
        self.end_headers()
        if head_only:
            return
        block = b"LW-SLOW-" * 4096
        sent = 0
        started = time.time()
        while sent < total:
            n = min(len(block), total - sent)
            try:
                self.wfile.write(block[:n])
            except (BrokenPipeError, ConnectionResetError):
                return
            sent += n
            time.sleep(0.05)

    def handle_errors(self, path, head_only):
        kind = path.rsplit("/", 1)[-1]
        table = {
            "dns": ("DNS failure", "This host does not exist."),
            "refused": ("Connection refused", "Nothing is listening on this port."),
            "reset": ("Connection reset", "The peer dropped the connection."),
            "tls": ("TLS / certificate failure",
                    "A certificate that does not validate for this host."),
            "http": ("HTTP error", "The server returned an error status."),
        }
        if kind not in table:
            return self.reply(404, page("Not found", "<p>Unknown error kind.</p>"))
        title, detail = table[kind]
        body = (
            f'<div class="card" style="cursor:default">{detail}</div>'
            + nav([("/", "Back to the fixture index", "home")])
        )
        return self.reply(200, page(title, body))

    def record(self, kind, data):
        try:
            os.makedirs(PAYLOAD_DIR, exist_ok=True)
            with open(REPORT_PATH, "a", encoding="utf-8") as fh:
                fh.write(json.dumps({"kind": kind, "data": data}) + "\n")
        except Exception:
            pass


def html_escape(text):
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def parse_multipart(raw, content_type):
    """Minimal multipart/form-data reader (cgi is gone in Python 3.13+).

    Returns (filename, file_size, {field: value}). Good enough for a fixture:
    it understands the boundary, the Content-Disposition of each part and the
    final CRLF, which is all a browser sends.
    """
    fields, filename, size = {}, "(no file)", 0
    m = re.search(r'boundary="?([^";]+)"?', content_type, re.I)
    if not m:
        return filename, size, {"error": "no multipart boundary"}
    boundary = ("--" + m.group(1)).encode()
    for part in raw.split(boundary):
        part = part.strip(b"\r\n")
        if not part or part in (b"--", b""):
            continue
        head, _, payload = part.partition(b"\r\n\r\n")
        if not _:
            continue
        disposition = ""
        part_type = ""
        for line in head.decode("utf-8", "replace").splitlines():
            low = line.lower()
            if low.startswith("content-disposition:"):
                disposition = line
            elif low.startswith("content-type:"):
                part_type = line.split(":", 1)[1].strip()
        field = re.search(r'name="([^"]*)"', disposition)
        file_part = re.search(r'filename="([^"]*)"', disposition)
        key = field.group(1) if field else "unnamed"
        if file_part:
            filename = file_part.group(1) or "(empty name)"
            size = len(payload)
        else:
            fields[key] = payload.decode("utf-8", "replace")
        if part_type:
            fields.setdefault("_types", {})[key] = part_type
    return filename, size, fields


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    os.makedirs(PAYLOAD_DIR, exist_ok=True)
    print(f"Account-flow fixture on http://127.0.0.1:{PORT}")
    print(f"  login/basic credentials: {VALID_USER} / {VALID_PASS}")
    print(f"  submissions log: {REPORT_PATH}")
    with Server(("127.0.0.1", PORT), Handler) as httpd:
        httpd.serve_forever()
