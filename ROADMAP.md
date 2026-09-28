# LibreWolf Android browser-shell roadmap

## Architecture decision

LibreWolf Android keeps **GeckoView 147** as its standards-compliant web engine. It
implements the Android browser shell, permissions, prompts, storage, recovery and
system integration around that engine. It does not reimplement HTML, CSS,
JavaScript, WebAssembly, WebGL, cookies, service workers or other web-platform
primitives.

GeckoView was selected over Android WebView because it preserves LibreWolf's engine
identity and provides working Android Widevine L1 DRM support.

## Delivery principles

- A broken website must never terminate the browser process.
- Website access to Android capabilities always follows website → browser prompt → Android permission → user decision.
- HTTPS/certificate validation is never bypassed.
- Normal and private browsing remain separate.
- Local browser data is device-only; Firefox Sync remains absent.
- Every phase is compiled and exercised on a physical arm64 Android device before being marked complete.

## Phase 0 — Baseline and audit (complete)

- Record engine capabilities and browser-shell gaps.
- Keep LibreWolf naming and a single canonical app icon (`logo/app-icon.png`) across launcher, start page and About.
- Establish the physical-device regression flow.
- Track popup/new-window and DRM regressions.

## Phase 1 — P0 website compatibility and critical Android plumbing

### Navigation

- Address/search parsing and URL normalization.
- Redirects, back, forward, reload, stop, and progress.
- New tab/background tab and `target=_blank` handling.
- Long-press link/image context menu.
- External protocols and Android intents.
- Desktop/mobile site mode.

### Website interaction

- JavaScript alert, confirm, prompt, and before-unload dialogs.
- HTTP authentication prompts.
- File chooser/upload with Android document picker and camera capture.
- Website permission bridge for camera, microphone, location, notifications, media, DRM, clipboard, and storage.
- Popup blocker prompt.

### Downloads

- Authenticated/header-aware downloads.
- Progress, status, notifications, open/share/delete, pause/resume/cancel/retry where Android permits.
- Duplicate filenames, large files, MIME handling, and destination selection.

### Acceptance criteria

- Gmail/Drive/WhatsApp-style file upload paths work.
- OAuth/payment/DRM popup flows do not crash.
- HTTPS, mixed content, DNS, timeout, and certificate errors are handled safely.
- Ordinary pages, forms, redirects, and new windows remain stable.

## Phase 2 — P1 browser fundamentals

- Persistent/restorable normal tab state with private-tab isolation.
- Reopen closed tab; close other/right tabs; duplicate and background tabs.
- Recently closed list and richer new-tab page.
- History search/date-range deletion and recently closed restoration.
- Bookmark folders, edit/search/sort, favicon and import/export.
- Find in page with match count and next/previous.
- Text/image/link context actions.
- Form/autofill and local password-vault integration.

## Phase 3 — P2 Android integration

- Fullscreen video, audio focus/background media, media notification and Bluetooth routing.
- Picture-in-picture.
- Android share/open-with intents.
- Autofill, IME, rotation/configuration changes, edge-to-edge and system bars.
- PWA manifest/install flow, shortcuts, storage and share targets.
- Bluetooth/Web Share/WebAuthn bridges where GeckoView exposes safe APIs.

## Phase 4 — P3 security, privacy, and recovery

- Safe Browsing configuration and malicious-download warnings.
- Per-site permission storage/reset and deny-by-default sensitive permissions.
- Content-process crash/kill detection with tab-level recovery.
- Browser error pages for DNS, refused/reset connections, timeouts, TLS/certificate and HTTP failures.
- Out-of-memory handling, tab suspension, and session restore.
- Clear cache/cookies/site data/history/downloads independently.

## Phase 5 — P4 quality and release

- Performance profiling, startup and memory tuning, 60/90/120 Hz checks.
- RTL, Unicode, locale-aware date/number formatting and translations.
- Chrome/Gecko developer debugging compatibility and diagnostics UI.
- Tier-1 website, authentication, government/banking, heavy-app, media, upload/download and PWA regression suites.
- Signed release/AAB, automated CI device tests, upgrade/migration and crash telemetry-free diagnostics.

## Implementation status

Phases 0 through 5 are complete and were each compiled and driven on physical
arm64 hardware before being marked so. `TESTING.md` is the authoritative record of
what was exercised; this is the shape of it.

| Phase | Delivered |
| --- | --- |
| 0 — Baseline | LibreWolf naming, one canonical icon (`logo/app-icon.png`), the physical-device regression flow. |
| 1 / P0 | Navigation, pop-ups, prompts, origin-aware permissions, file and camera upload, HTTP authentication, downloads, Safe Browsing, error pages, notifications, Widevine DRM, external intents, form paths. |
| 2 / P1 | Tab persistence and private isolation, tab actions, recently closed, history, bookmarks with folders and import/export, the encrypted local password vault, find-in-page, context actions, favicons. |
| 3 / P2 | Fullscreen video with transient bars, picture-in-picture following the video element's aspect ratio, a real `MediaSession`, audio focus and noisy-route handling, edge-to-edge insets, rotation including PiP, share-into-browser, web-app install and display-mode chrome hiding. |
| 4 / P3 | Threats counted separately from trackers, risky-download confirmation, the per-site screen reading and resetting both the shell's and the engine's stores, per-tab crash and kill recovery, local error pages, memory-pressure tab suspension, independent clearing of cache, cookies, site data, history, bookmarks, passwords and downloads. |
| 5 / P4 | Refresh-rate request, a diagnostics screen reading the live process, platform-formatted sizes and times, plurals, remote debugging absent from release and forced off in the runtime, and a release variant that builds and runs under R8. |

Two design decisions are worth keeping because they are not obvious from the code:

- **Downloads run on an in-app engine** (`DownloadEngine`) rather than Android
  `DownloadManager`, which has no embedder pause/resume API. The app owns the
  transfer, the persisted byte offset, the MediaStore destination and a foreground
  service with Pause/Resume/Cancel actions.
- **A setting is only offered when a real engine API or real shell code backs
  it.** The eleven things GeckoView 147 cannot support are listed with their
  reasons in `docs/SETTINGS-PLAN.md`; they are not gaps to be filled later.

### Defects found by testing on hardware

Each of these was invisible in code review and visible only on a device.

- A web app whose manifest declared `scope: "/"` could strip the address bar, tab
  tray and menu from every page on that origin.
- The per-site screen listed one site's decision twice, because `Uri.host` drops
  the port. Both stores are now keyed `host:port`.
- A download could be silently lost: forwarding every URI the engine flagged
  `requestExternalApp` re-fetched it without cookies and bypassed the safety
  check.
- Every settings switch on a page that calls `refresh()` was dead. The switch had
  a write-through listener, and `CompoundButton.onRestoreInstanceState` restored
  the outgoing checked state into the rebuilt row and wrote it back over the
  user's choice. The switch is now display-only and the row owns the write.
- `URLUtil.guessFileName` resolves `application/pdf` plus
  `filename="invoice.pdf.exe"` to `invoice.pdf`, hiding the executable extension
  from both the safety check and the saved name. The name is now taken from
  `Content-Disposition`.
- The release variant had never built, because resource shrinking cannot run once
  per ABI split; and CI only ever built debug, so an R8 mistake would have shipped
  unnoticed.

### Open

Verification and release chores, not missing features. The full list with reasons
is under "Not covered" in `TESTING.md`; the load-bearing ones are a CI device job,
upgrading from an older installed release, and RTL rendering under a right-to-left
locale. Release signing is done: a key outside the repository signs v2 and v3, and
the signed APK was installed and driven on a Pixel 9a.

The signing-off device is the **iQOO I2214** (Android 16, SDK 36, arm64-v8a,
1080x2460 @ 396 dpi). Recent work was driven on a **Pixel 9a** (Android 17,
SDK 37) because that was the device available; the iQOO has had clean install,
tab restore and two 256 MB byte-perfect downloads re-verified, but its P1 UI pass
has not been redone against its geometry.

## Current verified baseline

- GeckoView 147 with hardware Widevine L1 on Pixel 9a (`arm64-v8a`).
- Correct `onNewSession` lifecycle, popup permission prompt, and popup close handling.
- Multi-tab/private-tab shell, normal-tab restore, recently closed tabs, history, bookmarks/folders/import/export, downloads with destination subfolder and duplicate names, AMO extensions, encrypted local password vault, search settings, and HTTPS/DoH/ETP controls.
- Explicit JS/auth/file/camera/share prompts, origin-aware media/location/notification permission bridge, web notifications, per-site reset, Safe Browsing/error pages, crash service, tab persistence/recovery, fullscreen/PiP/media session, PWA shortcut install, Android share intents, and system/light/dark themes.
- Pixel 9a checks passed for alert/confirm/prompt/before-unload, HTTP auth, popup/new window, DocumentsUI file selection/cancel, camera source and Android permission launch, media/location/notification site→Android flows, Widevine L1, downloads and folder selection, redirects, DNS/refused/TLS/Safe Browsing errors, Google/Wikipedia/GitHub/Gmail/Drive/WhatsApp entry pages, ordinary form submission, fullscreen/PiP, PWA shortcut, incoming share intent, and themes.
- P1 checks passed for duplicate/background/close/close-other/close-right/swipe/reopen-closed tab flows, normal restore with private non-restore, most-visited/recently-closed shortcuts, history search/date deletion, bookmark add/edit/move/search/sort/import/export, find-in-page match feedback, link/image/text context actions, and encrypted local login/autofill storage.
- P3 checks passed for the risky-download warning and its cancel/override paths, the double-extension case, the unencrypted-transfer case, a per-origin permission reset that is confirmed by the engine asking again, the privacy report's threat figure, and survival of a critical memory trim with four tabs open.
- P4 checks passed for a minified release build running on the device, remote debugging being absent from it, a 120 Hz request that is granted once the device's own cap allows it, locale-aware sizes and relative times, and privacy and per-site permission decisions surviving a process restart.
- App icon in launcher, start page, Settings/About and README, all from `logo/app-icon.png`.
