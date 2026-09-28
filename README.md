<p align="center">
  <img src="logo/app-icon.png" width="112" alt="LibreWolf Android app icon">
</p>

# LibreWolf Android

An Android privacy browser built on the **GeckoView** engine — the same engine as
Firefox Desktop — with the privacy posture of [LibreWolf](https://librewolf.net).

---

> ## ⚠️ Unofficial community project
>
> **This is not LibreWolf.** It is an independent, community-run port and it is
> **not affiliated with, endorsed by, or supported by the LibreWolf project or
> Mozilla**. No part of this repository is reviewed or audited by them.
>
> It is *inspired by* LibreWolf Desktop and reuses LibreWolf's branding and its
> published privacy defaults as the design reference. The upstream project is:
>
> | | |
> |---|---|
> | **Upstream project** | [librewolf.net](https://librewolf.net) |
> | **Upstream source (canonical)** | [codeberg.org/librewolf](https://codeberg.org/librewolf) |
> | **Upstream source (GitHub mirror)** | [github.com/librewolf-community/browser-linux](https://github.com/librewolf-community/browser-linux) — LibreWolf's canonical home is Codeberg; the GitHub repository redirects here |
>
> If you want the real LibreWolf on a desktop, use the upstream releases. If you
> want a Firefox-engine browser on Android, this project takes no money, sends
> nothing anywhere, and has no analytics — but you are running an unofficial
> build, and you should treat it that way.

---

## What this is, and what it is not

The LibreWolf desktop tree is a Firefox build system for Linux, Windows and
macOS. **It cannot be compiled into an APK.** This project is therefore a native
Android browser written in Kotlin: it takes LibreWolf's *privacy configuration*
as its specification and implements the Android browser shell around GeckoView,
which is the same rendering engine Firefox uses.

That distinction matters, so to be plain about it:

- **GeckoView** supplies HTML, CSS, JavaScript, WebAssembly, WebGL, cookies,
  service workers, WebRTC, WebAuthn, storage, and DRM.
- **This project** supplies everything Android-shaped: tabs, the address bar,
  prompts, permissions, downloads, history, bookmarks, the password vault,
  settings, media integration, and system integration.

It is a port *in spirit and in privacy posture*, not a recompilation of upstream
source. Where the two differ, the mapping is documented in
[Mapping from desktop LibreWolf](#mapping-from-desktop-librewolf).

**Engine:** GeckoView `147.0.20260212191108` — the newest stable whose AAR
targets `compileSdk 37`. Versions 154/155 require SDK 37.1, which has no stable
platform yet.

## Features

### Privacy and security

- **Strict tracking protection** by default, with per-category customisation.
- **Total Cookie Protection** — third-party cookies partitioned and isolated to
  the site you are on. Cookie banner handling is configurable.
- **Query-parameter stripping** using LibreWolf's own strip and allow lists, so
  tracking identifiers are removed from the URL before the request goes out.
- **HTTPS-only mode**, with a strict variant that upgrades every navigation.
  Certificate validation is never bypassed, and there is no "click through the
  warning" path.
- **Secure DNS (DoH)** with a provider picker — Quad9, LibreDNS, Wikimedia,
  DNS4All, Mullvad — and TRR left disabled, matching the LibreWolf default.
- **Global Privacy Control** (`Sec-GPC`) sent to sites that honour it.
- **Safe Browsing** against phishing and malware, with threats counted and
  reported separately from trackers.
- **No telemetry of any kind.** No analytics SDK, no crash-reporting service, no
  usage ping, no updater. Network permission is used only to load pages.
- **Local-only data.** There is no account, no cloud sync, and no Firefox Sync.
  History, bookmarks, cookies and passwords never leave the device.
- **HTTPS/certificate failures get a local error page** — unknown host, refused,
  reset, timeout, every TLS and certificate code, HTTPS-only blocks, and
  Safe Browsing blocks. No remote error page is fetched for a failed navigation.

### Permissions

- **Site → browser prompt → Android permission → user decision.** The browser
  prompt and the Android runtime permission are separate steps, and denying
  either one is remembered for that site.
- **Per-capability, per-site management** — camera, microphone, location,
  notifications and autoplay each have their own list of sites that can be
  allowed, blocked or forgotten.
- **Per-site reset that actually resets.** Clearing a site reads and writes both
  the shell's store and the engine's `StorageController`, so the engine agrees
  the permission is gone instead of asking again next visit.
- **Sensitive capabilities are denied by default.**

### Tabs and sessions

- Normal and **private tabs**, kept genuinely separate — private sessions use the
  engine's private mode and are never persisted or restored.
- Tab grid tray, **tab groups**, duplicate tab, open in background tab, close
  other/right, and swipe to close.
- **Recently closed** with one-tap restore, and most-visited shortcuts.
- **Session restore** across process death and device reboot.
- Configurable new-tab behaviour, close-button policy, and confirm-on-closing-
  several-tabs.

### Downloads

- **In-app download engine**, not Android `DownloadManager`, because the system
  API exposes no pause/resume to an embedder. The app owns the transfer, the
  persisted byte offset, the destination, and a foreground service.
- **Pause, resume and cancel** from a foreground notification. Resume issues a
  real HTTP `Range` request and continues from the exact on-disk offset, so a
  process kill mid-transfer does not corrupt the file.
- Warns before running an executable, a double extension like
  `invoice.pdf.exe`, or an unencrypted `http://` transfer. The saved filename
  comes from `Content-Disposition`, not the MIME type, so a disguised extension
  is caught.
- A public `Downloads` subfolder with duplicate-safe naming.

### History, bookmarks and the password vault

- **History** with search, date-range deletion, and per-entry deletion.
- **Bookmarks** with folders, moving between folders, search, sorting, and JSON
  import/export.
- **Encrypted local password vault.** Credentials are encrypted at rest with a key
  held in the **Android Keystore**, so the ciphertext on disk is useless without
  the device. Nothing is sent anywhere.
- **Favicons** in the tab tray, history list and bookmark list.

### Media

- Fullscreen video with transient system bars that reveal themselves on a swipe.
- **Picture-in-picture** that follows the video element's own aspect ratio and
  survives rotation.
- A real **`MediaSession`**: metadata, artwork, playback state, and lock-screen and
  Bluetooth transport controls.
- **Audio focus** handling — pause on permanent loss, pause-and-restore on
  transient loss or a duck request, and pause when the audio route becomes noisy
  or the output device disappears.
- Background media suspend/resume control.

### Web integration

- **Web app install** — an installable site becomes a pinned launcher shortcut
  using the site's own icon.
- **Display-mode chrome hiding** for `fullscreen` and `standalone` manifests,
  scoped correctly to the installed app and the launching shortcut.
- **Share into the browser** for text, HTML, subject lines and multiple items.
- **JavaScript dialogs**, HTTP authentication, and `beforeunload` all handled
  natively.
- **File and camera upload** through the system chooser, with camera capture.
- **Web notifications**, favicons, find-in-page with a live match counter, and
  long-press context menus for links, images and text.
- **HTTPS/certificate-safe external protocol handling** — `mailto:`, `tel:` and
  `intent:` go to another app; everything else goes to the download pipeline.
- **Extensions** through GeckoView's add-on manager, including installing
  **uBlock Origin** from Mozilla AMO.

### DRM

- **Widevine L1** works. The GeckoView DRM path is retained and was verified in
  hardware on a test device.

### Interface

- Firefox-style UI: official LibreWolf logo, a private-tab button, a
  tracker-blocked pill, and a bottom toolbar (home | address | tab count | menu).
- Menu bottom sheet, two-column tab-tray grid, grouped settings, and dedicated
  History, Bookmarks and Downloads screens.
- **Twelve-category settings hierarchy with search across every screen**, showing
  live values.
- System, light and dark themes, with a minimum font size floor and a
  force-dark option.
- Text scaling and accessibility support.
- Edge-to-edge windows with status, navigation and IME insets applied on every
  screen, and correct rotation handling including in picture-in-picture.
- A **diagnostics screen** reporting the engine build, ABI, requested versus
  granted refresh rate, child processes, memory and profile size from the live
  process — honest about what is a request and what the system granted.
- Requests the display's fastest refresh rate at the browser's own resolution.

## Deliberately not supported

GeckoView 147 does not expose the embedder API for these, so this project does
**not** pretend to offer them. There is no control in the UI for any of them,
because a setting that does nothing is worse than no setting.

| Not offered | Why |
|---|---|
| Resist Fingerprinting (RFP) | GeckoView 147 exposes no fingerprinting-protection API. It exists in newer engines and returns with a 154/155 engine once SDK 37.1 is stable. |
| Passkey / WebAuthn management | WebAuthn runs entirely inside the engine with no embedder hook. Passkeys work; there is nothing to manage. |
| Per-site zoom, reader mode, proxy configuration | No API in this build. Pinch-to-zoom still works; use a system VPN or proxy app for proxying. |
| Per-site storage sizes | The engine will not report them. Per-site *clearing* is real and offered. |
| Clear cookies/cache by time range | No engine API. History is this project's own store, so history-only date ranges are offered. |
| Permanent WebAPK install | Requires a Trusted Web Activity host. The manifest flow installs a pinned launcher shortcut instead. |
| Custom HTTP error pages | `ContentDelegate` has no `onHttpErrorResponse`; an HTTP 404/500 is the engine's page to render. Local pages cover network and security failures. |

The full reasoning, with the API each row was checked against, is in
[`docs/SETTINGS-PLAN.md`](docs/SETTINGS-PLAN.md).

## Download

**Latest: v1.1 — [GitHub Releases](https://github.com/palashsuryavanshi/LibreWolfAndroid/releases/tag/v1.1)**
(v1.0 is still available for reference)

Pick the split matching your device:

```bash
adb shell getprop ro.product.cpu.abi
```

| File | ABI | Size |
|---|---|---|
| `app-arm64-v8a-release.apk` | modern phones — nearly all of them | ~85 MB |
| `app-armeabi-v7a-release.apk` | 32-bit ARM phones | ~82 MB |
| `app-x86_64-release.apk` | emulators and x86 tablets | ~89 MB |

```bash
adb install app-arm64-v8a-release.apk
```

Or copy the file to the phone and open it, allowing install-unknown-apps when
prompted.

### Verify what you downloaded

Release builds are signed with APK Signature Scheme v2 and v3. To confirm an APK
genuinely came from this project and was not repackaged, check the signer against
the published fingerprint:

```bash
apksigner verify --print-certs app-arm64-v8a-release.apk
```

```
Signing certificate SHA-256:
23129a1c6d158bb795284a31cbbadb3636786d5f2b82e24ff4151724ec3c0907
```

If that digest does not match, do not install it.

### Upgrading, and one thing that will bite you

Future releases keep this package name and this signing key, so you can install
over the top and keep your data.

This project was previously published as `net.librewolf.android`. Android treats a
package rename as a **different app** — the two cannot update into each other. If
you installed an earlier build you must uninstall it first, and **uninstalling
deletes your history, bookmarks, cookies and password vault**. There is no way
around this; it is how Android identifies apps.

## Build outputs

GeckoView ships one `libxul.so` per ABI (143–155 MB each, stored uncompressed for
`mmap`), so a single fat APK would carry every engine — roughly 364 MB. The build
produces **per-ABI splits** instead, so each device gets only its own engine, with
identical features and quality:

| Artifact | Devices | Size |
|---|---|---|
| `app-arm64-v8a-release.apk` | modern phones, minified + signed | ~85 MB |
| `app-armeabi-v7a-release.apk` | 32-bit ARM, minified + signed | ~82 MB |
| `app-x86_64-release.apk` | emulators, minified + signed | ~89 MB |
| `app-arm64-v8a-debug.apk` | modern phones, unminified | ~160 MB |
| `app-armeabi-v7a-debug.apk` | 32-bit ARM, unminified | ~91 MB |
| `app-x86_64-debug.apk` | emulators, unminified | ~101 MB |

```bash
./gradlew assembleDebug          # all three splits
./gradlew assembleRelease        # three signed per-ABI APKs
./gradlew bundleRelease          # signed AAB
```

Release builds run R8. Resource shrinking is deliberately off: AGP runs the
resource shrinker once per ABI split and then cannot decide which of the three
outputs it is meant to package, so the release variant fails outright. The
per-ABI splits are what halve the download, so they stay and the resource
shrinker goes. The reason is recorded at the `shrinkResources` line in
`app/build.gradle`.

## Mapping from desktop LibreWolf

| Desktop LibreWolf | This app |
|---|---|
| `settings/librewolf.cfg` — strict tracking protection, dFPI, query stripping, HTTPS-only, Safe Browsing, GPC | Engine settings in `MainActivity.buildRuntimeSettings()`: ETP `STRICT`, `ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS` cookies plus partitioning, native query-parameter stripping with the LibreWolf strip/allow lists, configurable HTTPS-only, Safe Browsing, and `setGlobalPrivacyControl` |
| RFP (`privacy.resistFingerprinting`) | Not wired — GeckoView 147 exposes no fingerprinting-protection API. Returns with a 154/155 engine once SDK 37.1 is stable. |
| `settings/librewolf.cfg` DoH list (Quad9, LibreDNS, Wikimedia, DNS4All, Mullvad) and `network.trr.mode = 5` | DoH selector in Settings; the engine gets `setTrustedRecursiveResolverUri` with `TRR_MODE_DISABLED`, matching the LibreWolf default |
| `settings/distribution/policies.json` — DisableTelemetry, HttpsOnlyMode, LNA, no remote debugging | No telemetry SDK; Local Network Access guards on; Gecko form autofill is wired to the app's encrypted Android Keystore vault; remote debugging is absent from release builds and forced off at runtime |
| `settings/librewolf.cfg` — sanitize on shutdown | Engine-level `StorageController.clearData(ALL)` plus local history, session and permission cleanup on erase and shutdown; private sessions use the engine's real private mode |
| `assets/mozconfig.new` — `MOZ_TELEMETRY_REPORTING=0`, no crashreporter, no updater | No Firebase, no Crashlytics, no auto-updater |
| App icon from `logo/app-icon.png` | `app_name`, adaptive and round launcher icons, start page, Settings → About, About dialog, and this README |

## Notes

- **Package name:** `com.palash.librewolfandroid`. The application id and the
  release signing key are bound to each other; see
  [Application identity](TESTING.md#application-identity).
- **Toolchain:** AGP 9.4.1, Gradle 9.7.1 (wrapper committed), JDK 17+,
  `compileSdk 37`. `minSdk 26`, `targetSdk 34`. The app version is its own
  (`1.1`); the engine base is shown separately in Settings → About as upstream
  Firefox `156.0`, LibreWolf release `1`.
- **SDK 37** currently ships only as a beta platform (`android-37.2-beta3`).
  Local development and CI both mirror it to a stable `android-37` alias; see
  `.github/workflows/android.yml`. Delete the alias and install
  `platforms;android-37` normally once Google ships it stable.
- **Restart to apply:** engine privacy settings — cookies, HTTPS-only, GPC, DoH,
  JavaScript and Safe Browsing — are fixed when the runtime is created, so
  changing them in Settings takes effect on the next launch.
- **Remote debugging** has no switch in a release build, and the runtime forces it
  off regardless of what a preference says, so a value flipped on a debug install
  cannot open the socket after a release install.
- **DRM** was verified at hardware Level 1 on a Pixel 9a.
- **Testing:** roadmap phases 0–5 (P0–P4) are complete, and each was compiled and
  exercised on a physical arm64 device. What has *not* been verified is listed
  under "Not covered" in [`TESTING.md`](TESTING.md) — read it before relying on
  this for anything that matters. It has had no independent security review.

## Build from source

Requirements: a recent Android Studio (AGP 9.4 support), JDK 17+ (bundled), and
the SDK 37 alias described under Notes.

1. **File → Open** and select this folder.
2. Let Gradle sync finish. The wrapper `gradlew` and `gradle-wrapper.jar` are
   committed, so the correct Gradle 9.7.1 downloads automatically.
   `local.properties` with your SDK path is generated by Studio — never commit it.
3. **Run ▶** on a device or emulator, or **Build → Build APK(s)**.
   CLI equivalent: `./gradlew assembleDebug` (use `gradlew.bat` on Windows).

### Signing

Release builds are signed. The signing key is **not** in this repository: a
gitignored `keystore.properties` points at a keystore held outside the project
directory, so a clone, a mirror, or a zip of the source cannot leak it. A build
with no `keystore.properties` still succeeds and produces `-unsigned.apk`, so
nobody is locked out of building by a secret they do not have.

To publish a release:

```bash
git tag vX.Y.Z && git push origin vX.Y.Z
./gradlew assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Verify before publishing. R8 breakage is invisible to a debug build, and a
signature that does not match the key you intend to keep is not recoverable.

**Back up the signing key.** It is the app signing key, not an upload key, and
there is no Play App Signing in front of it to recover from. If it is lost, no
user can install an update over an existing install, and the only remedy is to
uninstall — which deletes their history, bookmarks, cookies and password vault.

Pushes to `main` and `beta` trigger `.github/workflows/android.yml`, which builds
and lints and uploads the debug APK as an artifact. It runs no device or emulator
test, and it has no signing key, so its release artifacts are unsigned.

## Project documents

| | |
|---|---|
| [`ROADMAP.md`](ROADMAP.md) | Architecture decision, delivery phases, defects found by testing on hardware |
| [`TESTING.md`](TESTING.md) | What was exercised on real hardware, what was not, and where the engine stops the shell. The honest record — read this. |
| [`docs/SETTINGS-PLAN.md`](docs/SETTINGS-PLAN.md) | Every setting, the API that backs it, and the eleven things GeckoView 147 cannot support |
| [`docs/ACCOUNT-TESTING.md`](docs/ACCOUNT-TESTING.md) | Credentialed third-party flow testing without using a real account |

## License

Mozilla Public License 2.0 — see [LICENSE](LICENSE), the same licence as
LibreWolf Desktop. The LibreWolf logo and name are the property of the LibreWolf
project and are used here to identify the inspiration for this unofficial port.
