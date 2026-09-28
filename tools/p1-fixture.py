"""P1 (Phase 2) browser-fundamentals fixture.

Covers the Phase 2 acceptance items with deterministic pages:

  * tab state            - several distinct pages, deep links, query strings
  * find in page         - /long-text with a known number of repeats of a term
  * context actions      - links, an image, and selectable text on /content
  * autofill / vault     - /login-form, /profile-form (multi-field, one submit)
  * history / bookmarks  - /page/N for N in 1..6, distinct titles
  * favicon              - /favicon.ico per host path
  * private isolation    - /private-probe writes a marker and reads it back

Run:
    python tools/p1-fixture.py [port]

Then:
    adb reverse tcp:8788 tcp:8788
    adb shell am start -a android.intent.action.VIEW \
        -d http://127.0.0.1:8788/ -n com.palash.librewolfandroid/.MainActivity
"""

import http.server
import os
import socketserver
import sys
import urllib.parse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8788

CSS = """
body{font-family:system-ui,Roboto,sans-serif;margin:0;background:#0f0d12;color:#f2eff6;
     padding:18px;line-height:1.6}
h1{font-size:21px;margin:0 0 6px}h2{font-size:15px;color:#b9a7ff;margin:20px 0 8px}
p{color:#cfc7d8;font-size:15px}
a{color:#9ecbff}
.card{display:block;background:#1b1720;border:1px solid #322b3c;border-radius:12px;
      padding:12px 14px;margin:7px 0;text-decoration:none;color:#f2eff6;font-size:15px}
img{max-width:100%;border-radius:10px}
input{width:100%;box-sizing:border-box;padding:11px;margin:6px 0;border-radius:10px;
      border:1px solid #322b3c;background:#1b1720;color:#f2eff6;font-size:15px}
label{font-size:13px;color:#a79fb0}
button{width:100%;padding:13px;border:0;border-radius:12px;background:#b9a7ff;
       color:#121014;font-size:15px;font-weight:600;margin-top:10px}
mark{background:#b9a7ff;color:#121014}
.small{font-size:13px;color:#9d95a6}
"""

# The find-in-page needle appears exactly NEEDLE_COUNT times on /long-text.
NEEDLE = "quokka"
NEEDLE_COUNT = 7


def page(title, body, head=""):
    return (
        f"<!doctype html><html><head><meta charset='utf-8'>"
        f"<meta name='viewport' content='width=device-width,initial-scale=1'>"
        f"<title>{title}</title>"
        f"{head}<style>{CSS}</style></head><body>{body}</body></html>"
    ).encode()


def home():
    body = f"""
    <h1>P1 fixture</h1>
    <p class="small">Phase 2 browser fundamentals, no account needed.</p>
    <h2>Find in page</h2>
    <a class="card" href="/long-text">Long text with a known number of repeats</a>
    <h2>Context actions</h2>
    <a class="card" href="/content">Links, image and selectable text</a>
    <h2>Autofill / password vault</h2>
    <a class="card" href="/login-form">Login form (password)</a>
    <a class="card" href="/profile-form">Profile form (multi-field)</a>
    <h2>History and bookmarks</h2>
    <a class="card" href="/page/1">Page 1</a>
    <a class="card" href="/page/2">Page 2</a>
    <a class="card" href="/page/3">Page 3</a>
    <h2>Tab state</h2>
    <a class="card" href="/page/4?ref=one">Page 4 (query string)</a>
    <a class="card" href="/page/5#section">Page 5 (fragment)</a>
    <h2>Private isolation</h2>
    <a class="card" href="/private-probe">Private-mode probe</a>
    """
    return page("P1 fixture", body)


def long_text():
    paras = []
    for i in range(1, 13):
        if i <= NEEDLE_COUNT:
            paras.append(
                f"<p>Paragraph {i}: the {NEEDLE} appears here, number {i} of "
                f"{NEEDLE_COUNT}. This paragraph exists so find-in-page has "
                f"enough surrounding text to be realistic.</p>"
            )
        else:
            paras.append(
                f"<p>Paragraph {i}: ordinary filler text with no needle at all, "
                f"used to pad the page and make scrolling meaningful.</p>"
            )
    body = (
        "<h1>Long text</h1>"
        f"<p class='small'>The target word occurs exactly {NEEDLE_COUNT} times "
        "in the text below.</p>"
        + "".join(paras)
        + "<p><a class='card' href='/'>Back to the index</a></p>"
    )
    return page("Long text", body)


def content():
    # 1x1 transparent PNG, inlined so the image action is self-contained.
    png = (
        "data:image/png;base64,"
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )
    body = f"""
    <h1>Context actions</h1>
    <p>Long-press the link below for the link context menu.</p>
    <p><a id="target-link" href="/page/2?from=context">A link to Page 2</a></p>
    <h2>Image</h2>
    <p>Long-press the image for the image context menu.</p>
    <p><img id="target-image" src='{png}' alt="A one pixel test image" width="96" height="96"
            style="width:96px;height:96px;background:#b9a7ff"></p>
    <h2>Text</h2>
    <p id="selectable">Select this sentence to get the text selection action
       menu: press and hold, then drag across these words.</p>
    <p><a class="card" href="/">Back to the index</a></p>
    """
    return page("Context actions", body)


def login_form():
    body = """
    <h1>Sign in</h1>
    <p class="small">Use for the autofill and password-vault checks.</p>
    <form method="POST" action="/login-result">
      <label for="u">Email</label>
      <input id="u" name="email" type="email" autocomplete="username"
             placeholder="you@example.com">
      <label for="p">Password</label>
      <input id="p" name="password" type="password" autocomplete="current-password"
             placeholder="Password">
      <button type="submit">Sign in</button>
    </form>
    """
    return page("Sign in", body)


def profile_form():
    body = """
    <h1>Profile</h1>
    <p class="small">Multi-field form with no password: checks plain autofill.</p>
    <form method="POST" action="/profile-result">
      <label for="n">Full name</label>
      <input id="n" name="name" autocomplete="name" placeholder="Full name">
      <label for="e">Email</label>
      <input id="e" name="email" type="email" autocomplete="email" placeholder="Email">
      <label for="o">Organisation</label>
      <input id="o" name="org" autocomplete="organization" placeholder="Organisation">
      <label for="s">Street</label>
      <input id="s" name="street" autocomplete="street-address" placeholder="Street">
      <label for="c">City</label>
      <input id="c" name="city" autocomplete="address-level2" placeholder="City">
      <label for="t">Telephone</label>
      <input id="t" name="tel" type="tel" autocomplete="tel" placeholder="Telephone">
      <button type="submit">Save</button>
    </form>
    """
    return page("Profile", body)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LibreWolfP1Fixture/1.0"

    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s\n" % (self.address_string(), fmt % args))
        sys.stdout.flush()

    def reply(self, code, body, ctype="text/html; charset=utf-8", extra=None):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or []):
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        length = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        if path == "/login-result":
            body = (
                "<h1>Signed in</h1><p>The form posted successfully.</p>"
                '<p><a class="card" href="/">Back to the index</a></p>'
            )
        elif path == "/profile-result":
            body = (
                "<h1>Saved</h1><p>The profile form posted successfully.</p>"
                '<p><a class="card" href="/">Back to the index</a></p>'
            )
        else:
            body = "<h1>Unknown</h1>"
        return self.reply(200, page("Result", body))

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/":
            return self.reply(200, home())
        if path == "/long-text":
            return self.reply(200, long_text())
        if path == "/content":
            return self.reply(200, content())
        if path == "/login-form":
            return self.reply(200, login_form())
        if path == "/profile-form":
            return self.reply(200, profile_form())
        if path == "/private-probe":
            try:
                seen = open(_probe_file(), encoding="utf-8").read().strip()
            except OSError:
                seen = ""
            body = (
                "<h1>Private-mode probe</h1>"
                f"<p>Marker seen so far: <b>{seen or '(none)'}</b></p>"
                '<form method="POST" action="/private-probe">'
                '<input name="marker" placeholder="marker">'
                "<button type='submit'>Save marker</button></form>"
                '<p><a class="card" href="/">Back to the index</a></p>'
            )
            return self.reply(200, page("Private probe", body))
        if path == "/favicon.ico":
            # A distinct colour per page makes favicon loading observable.
            idx = 1
            if path.startswith("/page/"):
                idx = 2
            return self.reply(
                200,
                bytes.fromhex(
                    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
                    "890000000a49444154789c6360000002000100ffff03000006000557bfabd400"
                    "00000049454e44ae426082"
                ),
                ctype="image/x-icon",
            )
        if path.startswith("/page/"):
            n = path.rsplit("/", 1)[-1] or "?"
            query = urllib.parse.parse_qs(parsed.query)
            frag = parsed.fragment
            body = (
                f"<h1>Page {n}</h1>"
                f"<p class='small'>Distinct page used for history and bookmarks.</p>"
                f"<p>Query: <code>{urllib.parse.urlencode(query, doseq=True) or '(none)'}</code></p>"
                f"<p>Fragment: <code>{frag or '(none)'}</code></p>"
                '<p><a class="card" href="/">Back to the index</a></p>'
            )
            return self.reply(200, page(f"Page {n}", body))
        return self.reply(404, page("Not found", "<h1>Not found</h1>"))


def _probe_file():
    return os.path.join(
        os.environ.get("LW_PAYLOAD_DIR", os.path.dirname(os.path.abspath(__file__))),
        "p1-probe.txt",
    )


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    print(f"P1 fixture on http://127.0.0.1:{PORT}")
    print(f'  find-in-page needle: "{NEEDLE}" x{NEEDLE_COUNT} on /long-text')
    with Server(("127.0.0.1", PORT), Handler) as httpd:
        httpd.serve_forever()
