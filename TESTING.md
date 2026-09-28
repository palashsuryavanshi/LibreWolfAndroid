# LibreWolf Android acceptance record

This is the record of what has been exercised on real hardware, what has not, and
where the engine stops the shell from doing something. The regression checklist these\r\nrecords answer is at the end of this file.

Devices used: a Pixel 9a (arm64-v8a, 1080x2420 @ 405 dpi, Android 17 / API 37)
as the primary target, and an iQOO I2214 (Android 16 / API 36) as the earlier
release target.

`assembleDebug`, `lintDebug`, `assembleRelease` and `bundleRelease` all pass. The
release variant is built with R8 and the per-ABI splits; the resource shrinker is
off because AGP cannot decide which of the three split outputs to shrink, which
is recorded in `app/build.gradle`.

## Verified on device

**Navigation and pop-ups.** `window.open()`, `target=_blank`, OAuth and payment
handshakes, and `window.close()` complete without crashing the content process.
Before/after/repost dialogs, HTTP authentication, and `beforeunload` all resolve.
A user-initiated window can be set to take focus or wait in the background; the
setting is honoured either way, which is what a pop-up policy is for.

**Permissions.** A site request runs website prompt, then Android permission,
then the user decision, and denying either level is remembered for that site. The
per-site screen merges the shell's own store with the engine's
`StorageController.getAllPermissions()`, and resetting a site clears both copies â€”
a reset that only cleared the shell's copy left the site keeping a permission the
screen claimed it had removed.

Camera and microphone are the exception worth knowing about: **GeckoView 147 has no
`PERMISSION_CAMERA` or `PERMISSION_MICROPHONE` constant.** A camera request
arrives as a media source through `onAndroidPermissionsRequest`, not as a content
permission, so the shell records those two under its own keys and the engine keeps
no copy. The per-site screens therefore have real data for them, and the engine
still asks every time, because there is nowhere for it to remember.

**Downloads.** A file type that can execute, a double extension such as
`invoice.pdf.exe`, and a plain-`http://` transfer while HTTPS-only mode is on all
require explicit confirmation, and nothing is fetched before the answer. The saved
name comes from `Content-Disposition` rather than the MIME type, because
`URLUtil.guessFileName` resolves `application/pdf` plus `filename="invoice.pdf.exe"`
to `invoice.pdf`, which hid the executable extension from both the check and the
filename. Two 256 MB downloads completed byte-perfect on the iQOO.

**PDFs.** `pdfjs.disabled` is set at runtime. With the built-in viewer on,
GeckoView consumes an `application/pdf` response inside the page and the embedder
never sees it, so PDFs were neither saved nor checked.

**External protocols.** `mailto:`, `tel:`, `intent:` and similar are handed to
another app; every other file goes to the download pipeline. Forwarding everything
the engine flagged `requestExternalApp` re-fetched the URL without cookies,
bypassed the safety check, and usually ended in a "not available" toast with
nothing saved. Empty and unparsable URIs are dropped rather than launching a
content-less intent.

**Blocking and reporting.** `ContentBlocking.BlockEvent` distinguishes
anti-tracking, Safe Browsing and cookie categories, so a phishing page blocked by
Safe Browsing is counted as a threat rather than as a tracker, and the toolbar pill
reads "N threats blocked" when a threat is the more serious thing on the page.

**Recovery.** A content-process crash is recoverable without closing the browser,
and `onTrimMemory` at `RUNNING_CRITICAL` with four tabs open left the process
alive with the tab count intact and the active page rendered.

**Media.** Fullscreen hides the system bars, picture-in-picture uses the video
element's own aspect ratio and survives rotation, and the media session exposes
metadata, artwork and transport controls. Bluetooth routing needs no permission:
audio reaches a paired headset through the platform audio stack, which is why the
browser declares no Bluetooth permission at all.

**Web apps.** An installable site becomes a pinned launcher shortcut rather than a
WebAPK, because a WebAPK needs a Trusted Web Activity host. Chrome hiding is
scoped to the installed app and only when the browser is launched from its
shortcut; scoping it to the origin alone stripped the address bar from every page
on a host that shipped a `standalone` manifest.

**Settings.** The hierarchy is `SettingsComponents.kt` (components, renderer,
search index), `SettingsScreens.kt` (the hierarchy as data) and `SettingsActivity`
(draws any screen from its id), so navigation, insets, theming and the back stack
are written once. Search finds settings on any screen and shows live values.

Search engines are offered in a curated order rather than alphabetically, and the
choice is stored **by name**. It was stored by position, which meant reordering the
list would have silently moved every existing user to whichever engine took their
slot; a position written by an older build is now read through
`LibreWolfDefaults.LEGACY_SEARCH_ORDER`, which keeps the old positions and the
current names.

**Remote debugging is gated on the build type.** The switch is absent from a
release build and the runtime sets `remoteDebuggingEnabled` to
`BuildConfig.DEBUG && store.remoteDebugging`, so a preference flipped on a debug
install cannot open the socket after a release install.

**Release build on device.** The arm64 release APK was signed with a throwaway key,
installed over the debug build and driven: the fixture video rendered, the autoplay
prompt came from the engine, settings rendered, and logcat showed no
`ClassNotFoundException`, `NoSuchMethodError` or fatal exception. R8 did not break
the engine or the delegates. In that same build the Advanced section ends at Local
Network Access with no remote-debugging row, and Diagnostics shows no Developer
section, both of which are present in a debug build.

## Application identity

The application id is **`com.palash.librewolfandroid`**, and it is the identity the
release key is bound to.

It was previously `net.librewolf.android`. That was the wrong namespace to ship
under: `net.librewolf` is Mozilla/LibreWolf's domain, and a package name is a
claim about who owns the code. The rename moved `namespace`, `applicationId`, the
source tree under `app/src/main/java/com/palash/librewolfandroid/`, the `package`
declaration in all 48 Kotlin files, the manifest's media actions, the eleven
ProGuard `-keep` rules, the launcher shortcuts, and two test fixtures.

`com.palash.librewolf-android` was the first spelling tried and does not exist as
an option: AAPT rejects a hyphen in the manifest package outright with *"not a
valid Android package name"*, because a package name is a Java package name.

A rename is a new app to Android, not a rename of one. The two cannot be updated
into each other, so anyone who installed the old id has to uninstall it, and
doing so deletes their history, bookmarks, cookies and password vault.

Verified on the signed arm64 release after the rename: the built manifest reports
`package="com.palash.librewolfandroid"` with the launchable activity in the same
namespace, and `aapt2` confirms the `FileProvider` authority resolved to
`com.palash.librewolfandroid.fileprovider`. That last one is the classic rename
break — the manifest uses `${applicationId}.fileprovider` while the code builds
the same authority from `packageName` at runtime, and the two had to move
together or every file share would throw. `example.org` loaded, the menu opened,
the settings hierarchy rendered, the process kept its PID, and logcat was clean.

## Release signing

Release artifacts are signed. The key is RSA 2048 with SHA-384, stored as a
PKCS12 keystore **outside the repository**, at a path recorded in a gitignored
`keystore.properties` that also holds its passwords. Neither the key nor its
credentials are in git, and a clone, a mirror or a zip of the project directory
cannot leak them. A build with no `keystore.properties` still succeeds and emits
`-unsigned.apk`, so nobody is locked out of building by a secret they lack; the
build prints which of the two it is doing.

APKs are signed with **v2 and v3**, deliberately not v1. `minSdk` is 26, so v2
covers every supported device, and v2 signs the whole APK, which is what closes
the Janus hole (CVE-2017-13156). v3 is enabled because it is what makes a future
**signing-key rotation** possible while updates stay installable — and with no
Play App Signing in front of it, rotation is this project's only recovery path if
the key is ever exposed.

Verified with `apksigner verify` on all three splits: each reports `Verifies`,
v2 and v3 true, one signer, and the certificate DN and SHA-256 match the
keystore. The AAB is JAR-signed and `jarsigner -verify` reports `jar verified`
with the same DN.

The signed arm64 release APK was then installed on a Pixel 9a and driven, which
is the check that matters, because R8 breakage is invisible to a debug build:
`example.org` loaded, the menu sheet opened, the settings hierarchy rendered, the
process kept the same PID throughout, and logcat held zero `FATAL EXCEPTION`,
`ClassNotFoundException`, `NoSuchMethodError`, `NoClassDefFoundError`,
`VerifyError` or `IllegalAccessError`. The release gate held: the Developer
options screen carries **no switch widgets at all** and states the absence
rather than hiding it.

**This key is the app signing key, not an upload key.** There is no Play App
Signing to recover it from. If it is lost, no user can install an update over an
existing install; the only remedy is to uninstall, which deletes their history,
bookmarks, cookies and password vault. Back up both `keystore.properties` and the
`.jks` somewhere you will still have them.

## Not covered

Listed because an untested path is a claim nobody has made yet.

- **Upgrade from an older installed release.** There is no schema version in the
  preference files. Every persisted record is read through tolerant accessors
  (`optString`, `optInt`) so a format change degrades to defaults rather than
  throwing, but a real migration hook is needed before the first breaking change.
- **RTL layout and Unicode text.** `supportsRtl` is declared and no layout or
  source file hardcodes left/right, gravity or margins, which was checked by
  reading every layout and source file, but the app has never been run under a
  right-to-left locale.
- **An actual Safe Browsing verdict.** A local fixture cannot serve a page that a
  blocklist classifies as malware or phishing, so the threat counter and the "N
  threats blocked" pill have never been seen firing. The classification branch is
  exercised by the code path only.
- **A download blocked by Safe Browsing.** GeckoView 147 exposes no verdict API
  for a file the browser is about to fetch, so the warning is based on the file's
  own type, name and transport, which is what the shell can decide locally.
- **Transient audio-focus loss, becoming-noisy and output-device removal.** Both
  need a second audio client or a real headset, which the test device cannot
  fake. The focus request is confirmed registered; the loss callbacks were not
  driven.
- **Tab suspension detail.** Observed harmless under a critical trim, but which
  individual tabs were suspended, and reviving one by selecting it, were not driven
  end to end.
- **A per-origin reset against a real site** with a genuine `host:port`
  distinction. The fixture proves the engine's copy is cleared, on one port.
- **Bookmark-list favicons.** The history list is confirmed and the bookmark list
  uses the same `FaviconCache.bind` call, but no bookmark was created in the pass.
- **The Tier-1 site, authentication, banking and PWA regression sweep.** The
  public-site sweep in the P0 record is still the most recent one, and the
  credentialed third-party procedure in `docs/ACCOUNT-TESTING.md` still needs a
  real account.
- **Device tests in CI.** The workflow builds and lints only. There is no
  emulator or device job.
- **The iQOO I2214 has not had the P1 UI pass redone** against its 1080x2460
  geometry. It is the earlier release target; the Pixel 9a is what recent work was
  verified on. Its download Pause/Resume presses are in the same position: both
  256 MB transfers finished before a pause could land, because the adb tunnel was
  faster than on the Pixel. The actions render; a press has not been confirmed to
  freeze a transfer on that device.
- **Automatic sign-in from the password vault is not achievable through the public
  GeckoView surface.** GeckoView 147 installs its own package-private
  `Autofill.Delegate` on attach, so the app's delegate never receives field-focus
  events, and there is no scripting API to drive the fill. What ships instead is an
  explicit, origin-scoped "Saved login for this site" action that copies the
  username or password. Do not read the wired autofill path as fill-on-focus.
- **Copying a saved login to the clipboard** was never confirmed end to end; the
  paste probe was inconclusive.
- **What the notification shade actually shows on OriginOS is unconfirmed.** The
  `ic_notification.xml` resource is correct and `dumpsys` reports it as
  `drawable/ic_notification`, and the installed APK was verified byte-identical to
  the local build, so the resource is not the problem. The shade kept rendering the
  old artwork through force-stop, fresh notification tags and a full reinstall, and
  only changed after a reboot — which points at OEM icon substitution cached in
  state that survives reinstall. The vector may simply be invisible on that OEM.
- **The adaptive launcher icon is upscaled.** `logo/app-icon.png` is 192x192, so the
  xxxhdpi launcher icon is pixel-exact, but the xxxhdpi adaptive foreground (432)
  is a 2.25x upscale and will look soft. A 512 px export fixes it.

## Platform boundaries

These are not missing work. GeckoView 147 does not expose the API, so there is
nothing for the shell to call.

- **WebAuthn and passkeys** run inside the engine and expose no embedder hook, so
  there is no bridge to write and no setting to offer.
- **`GeckoSession.ContentDelegate` has no `onHttpErrorResponse`**, so an HTTP 404
  or 500 is rendered by the engine and the shell cannot substitute its own page.
  The local error pages cover the network failures: unknown host, connection
  refused and reset, timeout, the TLS and certificate codes, HTTPS-only, and the
  Safe Browsing codes.
- **PWA-declared share targets** are served by the page's own service worker, and
  `navigator.storage.persist()` has no embedder API. Neither can be intercepted.
- **A permanent WebAPK install** needs a Trusted Web Activity host, so the
  manifest flow installs a pinned launcher shortcut.
- **No proxy API**, so proxying is left to the system or a VPN app.
- **No zoom API** in this build, so per-site zoom is not offered. Pinch-to-zoom on
  a page still works.
- **Per-feature fingerprinting switches, ETP exceptions per site, per-site storage
  sizes, and a time range for clearing cookies or cache** are all absent because the
  engine exposes no API for them. Canvas, WebGL, fonts, audio and screen metrics
  are reduced together by the tracking-protection level.
- **The refresh rate is a request, not a setting.** The system decides whether a
  frame-rate request is granted; a display with adaptive refresh may grant it,
  defer it or refuse it, and a user setting can refuse it outright. Diagnostics
  reports the request and the result side by side rather than claiming a rate.
  The Pixel 9a's 60 Hz turned out to be its own "Smooth display" cap, which
  Diagnostics now names.
- **`View.setRequestedFrameRate` is API 35 and `Activity.getDisplay` is API 30**
  while the app supports API 26, so the frame-rate hint is applied on newer
  releases only and the display is reached through
  `ContextCompat.getDisplayOrDefault`.
- **`targetSdk` is 34 while `compileSdk` is 37.** Bumping it would newly enforce
  edge-to-edge and predictive back on Android 15+, which interacts with the shell's
  own inset handling, so it is a release decision rather than an implementation
  detail.
- **`GeckoPreferenceController.setGeckoPref` is marked experimental**, so the
  `pdfjs.disabled` call is opted into explicitly.

## Test fixture

`tools/p2-fixture.py` on port 8799 through `adb reverse`. Routes: `/risky` (risky
content types), `/payload.apk`, `/payload.exe`, `/payload.pdf`,
`/invoice.pdf.exe`, `/tabs` (three `window.open` calls), `/pop` (one window per
tap), `/media`, `/audio`, `/app`, `/hits`, and `/ua`, which reads the `User-Agent`
the engine actually put on the request rather than what a page's script believes.

`--tls` serves the same pages on port 8800 behind a throwaway self-signed
certificate generated into the temp directory, which is how the certificate-error
path is driven: `https://127.0.0.1:8800/` renders "Secure connection failed â€” A
secure connection could not be established. LibreWolf will not bypass certificate
validation."

## Known defect, fixed

**A web app's display mode could trap the whole origin.** Chrome was hidden for
any page on an origin that shipped a `fullscreen` or `standalone` manifest, so a
site declaring `scope: "/"` stripped the address bar, tab tray and menu from every
page on that host.

**The per-site screen listed one site's decision twice**, as `127.0.0.1` from the
engine and `127.0.0.1:8799` from the shell, because `Uri.host` drops the port. Both
stores are now keyed `host:port`.

**A download could be silently lost**, described above under External protocols.

**Every settings switch on a page that refreshes was dead.** A switch row gave the
`SwitchCompat` a write-through `OnCheckedChangeListener` *and* made the row's click
handler call `toggle()`. Because `refresh()` is `activity.recreate()`, a tap tore
the activity down mid-gesture, and `CompoundButton.onRestoreInstanceState` then
restored the outgoing checked state into the rebuilt row â€” which the write-through
listener could not distinguish from the user's tap, so it wrote the old value back.
The switch is now display-only and the row owns the write, taking the next value
from the store rather than from the switch, and the re-creation is posted so the
tap finishes first.

## Regression checklist

Run on a physical arm64 device before a release.

### Build and install

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:assembleDebug --no-daemon
adb -s <serial> install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

## P0 critical paths

- [x] `window.open()`, `target=_blank`, OAuth/payment popup, and `window.close()` do not crash.
- [x] `alert()`, `confirm()`, `prompt()`, before-unload, repost, and HTTP authentication dialogs resolve.
- [x] `<input type=file>` opens Android DocumentsUI; multiple selection and camera capture work.
- [x] Camera/microphone, location, notification, autoplay, and storage requests follow website prompt → Android permission → user decision.
- [x] Widevine L1 initializes and a DRM test page plays without a browser crash.
- [x] Google, Wikipedia, GitHub, Gmail/Drive/WhatsApp entry pages, representative public/government surfaces, and ordinary form upload/submission paths load and remain stable.
- [x] HTTP/HTTPS, redirects, DNS failure, timeout, refused connection, TLS failure, and Safe Browsing blocks show a useful error/blocked page.
- [x] External protocols and Android share intents route correctly.

## P1 fundamentals

- [x] New, private, background, duplicate, close-other, close-right, swipe-close, and reopen-closed tabs work.
- [x] Normal tabs restore after process death; private tabs never restore.
- [x] Most-visited, bookmarks, history search/date-range delete, bookmark folders/import/export, and find-in-page work.
- [x] Long-press link/image actions and text selection actions work.

## P2 Android integration

- [x] Fullscreen video hides system bars; Picture-in-picture and media notification work.
- [x] Web Share, Android share/open-with, downloads, notifications, autofill, rotation, and edge-to-edge behavior work.
- [x] PWA manifest exposes an install shortcut and service-worker window handling does not crash.
- [x] Media session exposes metadata, artwork and playback state to the lock screen and Bluetooth controls; the notification carries working play/pause and stop actions.
- [x] Audio focus: playback pauses on permanent loss and pauses then restores on a transient loss or duck request; becoming-noisy and output-device removal pause playback.
- [x] Picture-in-picture uses the video element's own aspect ratio and survives rotation.
- [x] Every screen is edge to edge: status bar, navigation bar, display cutout and keyboard insets are applied, and the bar icons follow the light/dark theme.
- [x] Rotation and configuration changes keep the window, chrome and PiP correct.
- [x] Share into the browser handles plain text, HTML, a subject line and multiple shared items.
- [x] Web app install uses the site's own manifest icon, and `display: fullscreen`/`standalone` hides the browser chrome on that origin.

## P3 security, privacy, and recovery

- [x] Safe Browsing blocks are counted as threats, not trackers, and the privacy report shows both figures.
- [x] Malicious-download warnings: executables, installers, double extensions and unencrypted transfers require explicit confirmation.
- [x] Per-site permission storage and reset cover both the shell's and the engine's stores, and reset really clears the engine.
- [x] Content-process crash/kill detection recovers at tab level.
- [x] Error pages for DNS, refused/reset connections, timeouts and TLS/certificate failures.
- [x] Out-of-memory handling suspends the least recently used background tabs and keeps the tab list.
- [x] Cache, cookies, site data, history, bookmarks, passwords and downloads clear independently.

## P4 quality

- [x] Remote debugging is opt-in and ordinary release builds do not expose it: the switch is absent from the release build and the runtime ignores the stored preference unless the build is debuggable.
- [x] The browser asks for the display's fastest refresh rate at the current resolution, and Diagnostics reports what was requested against what was granted.
- [x] The release variant builds (per-ABI APKs and an AAB) and the minified build runs on a physical device.
- [x] Byte counts, percentages and relative times are locale-aware rather than hand-formatted.
- [x] Privacy settings and per-site permission decisions survive a process restart.
- [ ] RTL/Unicode text and long-download stability.
- [ ] Upgrade from an older installed release, and the Tier-1 site/authentication/banking regression sweep.

Items still unticked were not exercised in this pass; they are not known-failing.
Run them before a release and record the result here.

