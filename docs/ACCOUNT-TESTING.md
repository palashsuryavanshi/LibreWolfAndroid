# Account and credentialed-flow testing

Two different things are called "account testing" here, and only one of them needs
real credentials.

1. **Browser-side mechanics** — cookies, redirects, login walls, OAuth popups,
   HTTP auth, authenticated downloads, uploads, share targets. These are what the
   browser actually implements, and they are fully testable with **no account at
   all** using the local fixture.
2. **Third-party services** — Gmail, Drive, WhatsApp Web, a bank portal. These
   can only be validated by someone with their own account. This project stores
   no credentials and must never receive them.

## 1. Credential-free fixture (run this first)

`tools/account-flow-fixture.py` is a local HTTP server that reproduces every
browser-side mechanic an account flow depends on. It uses throwaway credentials
(`tester` / `correct-horse`) that exist only inside the fixture.

```powershell
python tools\account-flow-fixture.py 8777
adb -s <serial> reverse tcp:8777 tcp:8777
adb -s <serial> shell am start -a android.intent.action.VIEW `
    -d http://127.0.0.1:8777/ -n com.palash.librewolfandroid/.MainActivity
```

Set `LW_PROTECTED_BYTES` to change the size of the cookie-protected download
(default 8 MiB); a larger value makes pause/resume easy to exercise.

### What each page proves

| Page | Mechanic under test |
| --- | --- |
| `/login` → `/protected` | Form POST, session cookie, 302 chain, login wall |
| `/session` | Exact `Cookie` header the engine sends |
| `/basic` | HTTP authentication prompt, realm display, retry with credentials |
| `/oauth/start` | `window.open`, popup lifecycle, `postMessage`, `window.close` |
| `/upload` | Android document picker, multipart upload, file name/size round-trip |
| `/download-protected` | **Authenticated download** — the case GeckoView cannot expose to us |
| `/repost` | POST followed by reload → repost warning |
| `/external` | `tel:` / `mailto:` / `market:` intent routing |
| `/pwa` | Web app manifest, service worker, install/shortcut flow |
| `/share-target` | POST body from an Android share |
| `/crash` | Content-process kill and tab-level recovery |
| `/slow` | Streaming response, timeouts, background behaviour |
| `/errors/*` | DNS / refused / reset / TLS / HTTP error pages |

Submissions (uploads, share posts) are appended to a log under
`%TEMP%\librewolf-account-fixture\submissions.log` so a run can be checked
without reading the screen.

### The authenticated-download case, in detail

GeckoView 147 exposes **no API to read its cookie jar**. An app that re-issues a
download request with its own HTTP client therefore cannot attach the session
cookie, and a download from a signed-in page silently returns the *login page*
instead of the file.

LibreWolf avoids this by consuming the response Gecko has **already** fetched
(`GeckoSession.ContentDelegate.onExternalResponse` → `WebResponse.body`). Gecko
applies the cookie; the app only writes the bytes to disk. Resuming a paused
authenticated download re-fetches through Gecko in a throwaway session and skips
the bytes already on disk, so a file is never duplicated or truncated.

Verify with:

```powershell
# after signing in on the device and downloading the protected file
adb -s <serial> shell md5sum /sdcard/Download/fixture-protected.bin
Get-FileHash $env:TEMP\librewolf-account-fixture\protected.bin -Algorithm MD5
```

The two hashes must match.

## 2. Real-account testing (release team only)

This cannot be automated from this repository and must never be given real
credentials. Whoever runs it should use a personal account with no sensitive
data, and should expect to be asked for two-factor prompts.

For each service, record: device, Android version, build version, and whether the
flow passed, failed, or was blocked by the service.

### Google account (Gmail / Drive)

- [ ] Sign in at `accounts.google.com`. Expect the Google sign-in page, not a
      download or an error page.
- [ ] Complete any 2-step verification. The prompt should come from Google.
- [ ] Open Gmail. Inbox renders; scrolling and search work.
- [ ] Compose and send a message to yourself. It must appear in Sent and in the
      conversation.
- [ ] Open Drive, enter a folder, preview a PDF, then download it.
      The download must be the real file, not the sign-in page — compare its
      size with the size shown by the service.
- [ ] Sign out, then confirm the next visit shows the sign-in wall again.
- [ ] Repeat the whole flow in a private tab. The session must not be reused.

### WhatsApp Web

- [ ] Load `web.whatsapp.com`, link the device with the QR code, and confirm the
      message list renders.
- [ ] Send a message to a self-chat and confirm it appears.
- [ ] Reload and confirm the session is restored.

### Any site behind HTTP authentication

- [ ] Load a page that returns `401` with a `WWW-Authenticate` header.
- [ ] Confirm the browser's own prompt appears with the correct host and realm.
- [ ] Cancel once, then reload: the prompt may be suppressed (standard browser
      behaviour). Sign out or use a different realm to get it back.
- [ ] Enter correct credentials and confirm the page loads.
- [ ] Enter wrong credentials and confirm a retry prompt appears.

### Banking / government portals (treat as highest risk)

- [ ] Sign in and complete any challenge (OTP, captcha, USB key).
- [ ] Navigate several sections, download a statement, and upload a document.
- [ ] Confirm no crash, no certificate warning, and no request for a permission
      the site does not need.
- [ ] Rotate the password afterwards.

### Recording the outcome

Attach the screenshots and the fixture/server logs to the release ticket. Mark a
service as *blocked* rather than *failed* when the service itself refuses the
test account — that is not a browser defect.
