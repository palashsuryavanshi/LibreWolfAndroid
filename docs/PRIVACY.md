# Privacy model — LibreWolf Android

Written for Phase 6. Every statement here was traced in the source or measured on
a device; where something is a limitation rather than a guarantee, it says so.
Intended as the technical basis for a Play Data Safety declaration.

Engine: GeckoView 147.0.20260212191108. No analytics, no telemetry, no
advertising, no crash-reporting SDK, no network calls of its own.

---

## 1. What leaves the device

**Nothing the app itself sends anywhere.** The application makes no network
requests outside GeckoView's web content. There is no analytics endpoint, no
config fetch, no telemetry ping, no attribution, no crash upload.

All traffic is page traffic, sent by Gecko on the user's behalf, to the host the
user asked for. There is one exception worth naming: DNS-over-HTTPS when the user
has enabled it, which sends DNS queries to the configured resolver. It is off by
default (`dohMode` disabled) and is user-configurable.

GeckoView bundles Google Play Services code (it appears in the merged manifest as
`GoogleApiActivity`) for its Google-dependent features. That is engine code, not
app code; this app adds no calls into it.

## 2. Data collected about the user

**Nothing.** No device identifiers are read. No advertising ID. No install
referrer handling. No user account. There is no account system and no sync.

## 3. Data stored on the device

All of it is inside the app's private data directory
(`/data/data/com.palash.librewolfandroid/`), which no other app can read without
root or a debugger.

| Data | Where | Created | Encrypted | Backed up | Deleted by |
|---|---|---|---|---|---|
| History (URL, title, time) | `shared_prefs/librewolf_history.xml` | page commit | no | **no** | Settings → Clear |
| Bookmarks | `shared_prefs/librewolf_bookmarks.xml` | user action | no | **no** | Settings → Clear |
| Site permissions | `shared_prefs/site_permissions.xml` | user decision | no | **no** | site-data clear |
| Saved sessions | `shared_prefs/browser_sessions.xml` | page commit / pause | no | **no** | session overwrite |
| Download list | `shared_prefs/librewolf_downloads.xml` | download start | no | **no** | per-item / clear finished |
| Settings | `shared_prefs/librewolf_privacy.xml` | user action | no | **no** | app data clear |
| Password vault | `shared_prefs/librewolf_vault.xml` | user saves a login | **yes** | **no** | per-item / clear all |
| Cookies, cache, localStorage, IndexedDB, service workers | Gecko profile `files/mozilla/*.default/` | engine | no | **no** | engine data clear |
| Camera captures | `cache/uploads/` | file input with camera | no | **no** | cache eviction |
| Crash minidumps | Gecko profile `minidumps/` | engine crash | no | **no** | profile cleanup |

Nothing is encrypted at rest except the password vault. Browsing history, URLs
and cache are plaintext inside the app sandbox, which is the same posture desktop
Firefox uses and is why no marketing claim is made about data-at-rest encryption.

### The password vault

`LoginStore` seals credentials with **AES-256-GCM** under a key generated in the
**Android Keystore** (`KeyGenParameterSpec`, `BLOCK_MODE_GCM`, random 12-byte IV
from the keystore, stored as `iv || ciphertext`). No custom cryptography, no
key derivation, no password-based KDF. The keystore key is non-exportable and
**is not backed up**, so a restored copy of the vault would be unreadable — which
is a second, independent reason backup is off.

## 4. Private browsing

Private tabs are real Gecko private contexts: `GeckoSessionSettings.usePrivateMode
(true)`. The UI label is not what makes them private.

**Never written anywhere:**
- history, recently-visited, address suggestions (`onPageStop` guards on
  `!tab.isPrivate`; `saveTabState` filters private tabs out)
- favicons (`FaviconCache.load` is not called for private tabs)
- saved sessions (`saveTabState` filters them)
- cookies, cache, DOM storage, IndexedDB, service-worker data

**Measured, not assumed.** A persistent cookie set in a private tab was absent
from `cookies.sqlite` after the tab was closed, while an identical cookie set in
a normal tab was present. The private one never reached disk. History entries and
session-store records for private visits were absent from both stores.

**Where private state does go, deliberately:**

- **Memory.** Gecko holds private cookies and storage in memory for the life of
  the session. That is the engine's mechanism, not a workaround.
- **The window.** A private tab in front sets `FLAG_SECURE`. Measured: a screen
  capture of a private tab returned 0.3% lit pixels (black, apart from system
  bars); the same page in a normal tab returned 87.9%. `dumpsys window` shows
  `SECURE` in the window flags only while a private tab is in front. This keeps
  private content out of the recents thumbnail, screenshots and screen sharing,
  without disabling capture for ordinary browsing.
- **Notifications.** Web notifications from a private page are suppressed. A
  transport notification for private media drops to `VISIBILITY_PRIVATE` and names
  nothing (see `private_media_playing`).
- **Downloads.** A file downloaded from a private tab is **still written to
  storage and still appears in the download list and notification.** This is
  stated plainly because it is the one place where "private" does not mean the
  file goes away. The user asked for a file; the file persists.

## 5. Tracking protection

Configured on `ContentBlocking.Settings` at runtime construction:

- Enhanced Tracking Protection: `DEFAULT`, or `STRICT` when the user enables it
- social tracker blocking, configurable
- `queryParameterStrippingEnabled` with the LibreWolf strip list
- `cookieBehavior` maps directly to the user's setting: `ACCEPT_NONE`,
  `ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS`, or `ACCEPT_ALL`
- private mode always uses `ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS`
- `globalPrivacyControlEnabled` follows the Do-Not-Track setting
- Safe Browsing: `DEFAULT`, disableable by the user

**What is not claimed.** No UI in this app says it blocks all trackers, all ads,
all malware, or all fingerprinting. ETP is a list-based mechanism with known
gaps; the app reports per-page tracker and threat counts from
`onSecurityChange`/`ContentBlocking` and calls them "trackers blocked", which is
a count of what the engine reported, not a guarantee.

Fingerprinting protection uses supported GeckoView settings
(`setFingerprintingProtection`, `setBaselineFingerprintingProtection`) applied
after runtime creation. There is no custom fingerprint randomization: no random
screen sizes, timezones, fonts or canvas noise, which would make the browser
more unique rather than less.

## 6. Android permissions

Ten, all in the release manifest after Phase 6 removed
`HIGH_SAMPLING_RATE_SENSORS` that GeckoView's manifest declares by default.

| Permission | Why |
|---|---|
| `INTERNET` | browsing |
| `ACCESS_NETWORK_STATE` | connection type for DoH decisions |
| `CAMERA` | sites requesting camera; file input with capture |
| `RECORD_AUDIO` | sites requesting microphone |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | sites requesting geolocation |
| `POST_NOTIFICATIONS` | download and media notifications |
| `FOREGROUND_SERVICE` + `_MEDIA_PLAYBACK` + `_DATA_SYNC` | media playback and download progress |
| `WAKE_LOCK`, `MODIFY_AUDIO_SETTINGS` | merged in by GeckoView; audio routing |

None are requested at startup. Camera, microphone, location and notifications are
requested only when a site or the user actually needs them, and a denied
permission is reported back to the engine as a denial rather than a grant.

There is **no Bluetooth permission**; media routes through the platform audio
stack.

## 7. Exported components

Exactly two, both deliberate:

- `MainActivity` — the browser itself. Accepts `VIEW` for http/https, `SEND` for
  text/plain and text/html, and its own private-tab action.
- `androidx.profileinstaller.ProfileInstallReceiver` — exported by the AndroidX
  library, guarded by `android.permission.DUMP`, so only shell/root can invoke it.

Everything else is `exported="false"`: all settings and list screens, the
download service, the media service, the crash service, the FileProvider, and
the media-action receiver.

## 8. Untrusted input handling

- **Web → native.** A page-supplied `intent:` URL is parsed and then
  **stripped before launch**: an explicit `component`, `package` or `Selector` is
  refused outright, every `FLAG_GRANT_*` bit is dropped, and `content://`,
  `file://` and `android_app://` are refused. Only the action and a data URI
  survive. A normal `intent:` with a VIEW action and an http(s) target still
  opens.
- **Download filenames.** Server-supplied names go through `DownloadSafety.
  sanitise`: path separators stripped, reduced to a safe character set, leading
  and trailing dots removed, capped at 120 characters, falling back to
  `"download"`. `../../file` cannot escape.
- **Executables.** `.apk`, `.exe`, scripts and binaries are refused by default
  behind a confirmation. Nothing is auto-installed or auto-executed.

## 9. Known limitations

Stated as limitations, not fixed by claiming otherwise.

- **Downloaded files persist.** A file fetched in private mode stays on disk.
- **No certificate pinning.** Pinning is not implemented; the engine's normal
  validation and the system trust store are used.
- **Third-party cookies are on by default.** The LibreWolf default is
  `ACCEPT_ALL`; isolation is a setting, not the default. This mirrors desktop
  LibreWolf's own defaults but is worth knowing.
- **http/https behaviour is user-controlled.** HTTPS-only mode defaults to off
  (`allowInsecureConnections(ALLOW_ALL)` for http navigation and mixed content).
  This does **not** weaken certificate validation — certificates are validated
  either way.
- **History is plaintext** inside the app sandbox.
- **Extension support is in-app and user-installed**, via GeckoView's WebExtension
  API. Extensions have web-content-level power by design; the user installs them.