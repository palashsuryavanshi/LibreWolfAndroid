# Production-readiness audit — LibreWolf Android (`beta` @ `7224ef7`)

Date: 2026-10-05. Method: full source read of the `app` module (50 Kotlin files,
~10.8k lines, 25 layouts, 598 strings), manifest, Gradle, CI, ProGuard, plus
`assembleDebug` + `lintDebug` (BUILD SUCCESSFUL, 0 errors, 186 warnings).
Two bounded subagent scans covered the security surface and dead code; every
load-bearing claim below was re-verified against the source by hand. No code was
changed in this phase; the tree builds clean, so no repair commit was needed.

Read this with `TESTING.md` (what was driven on hardware) and `ROADMAP.md`.
Where this report says "verified", it means traced in code, not run on device.

---

## 1. Architecture summary

There is no MVVM, no repository layer, no DI, no ViewModel, no Fragments for
screens. The architecture is **one Activity holding the whole browser**:

- `MainActivity` (2,373 lines) owns: the tab list, every `GeckoSession`, all
  session delegates (navigation, progress, content, content-blocking, prompt,
  permission, autofill, selection, media), the address bar, suggestions, menu
  and tab sheets, downloads wiring, fullscreen/PIP,-JS dialogs via delegates,
  and quit/erase paths. It is the browser.
- 13 satellite activities are thin screens over SharedPreferences-backed stores
  (History, Bookmarks, Downloads, Passwords, Extensions, Settings + search,
  SitePermissions, Diagnostics, Search engine pickers, TabsSettings,
  Onboarding). They do not hold browser state.
- State lives in seven small stores (`PrivacyStore`, `HistoryStore`,
  `BookmarkStore`, `BrowserSessionStore`, `SitePermissionStore`,
  `DownloadStore`, `LoginStore`), all SharedPreferences + JSON, all
  `MODE_PRIVATE`, all async `apply()`. Only `LoginStore` is encrypted
  (Android Keystore AES-256-GCM, sound implementation).
- Background work: `DownloadEngine` (3-thread pool, daemon threads),
  `FaviconCache` (2-thread pool), `DownloadService` (`dataSync` FGS),
  `BrowserMediaService` (`mediaPlayback` FGS). No coroutines, no WorkManager.
- Settings UI is declarative data (`SettingsScreens.kt`, 27 screens) rendered
  by `SettingsComponents.kt` into programmatic Views (no RecyclerView).

This is coherent and shippable for an app this size. The risk is not the
pattern, it is `MainActivity`'s size: every browser behavior change touches
the same file, and the delegate objects make control flow hard to follow.
Do not re-architect; do extract helpers (Phase C).

## 2. Project structure summary

```
app/                          single module
  build.gradle                AGP 9.4.1, compileSdk 37, minSdk 26, targetSdk 34,
                              versionCode 156004 / versionName 1.2beta2,
                              per-ABI splits, R8 on / shrinkResources off,
                              v2+v3 signing, key outside repo (gitignored)
  proguard-rules.pro          14 keeps (see §7 / §10)
  src/main/
    AndroidManifest.xml       10 permissions, MainActivity exported only,
                              VIEW http/https + SEND text, shortcuts, 2 FGS,
                              FileProvider, no backup (allowBackup=false),
                              networkSecurityConfig (cleartext off)
    java/…/librewolfandroid/ 50 files, one flat package (deliberate)
    res/                      layout×25, values + values-night (EN only),
                              drawable×52, mipmap×6 densities, xml×3, menu×2
tools/                        4 Python test fixtures (not shipped in APK)
docs/                         SETTINGS-PLAN, ACCOUNT-TESTING
```

CI (`.github/workflows/android.yml`): JDK 17, SDK alias for the 37 beta
platform, `lintDebug` → `assembleDebug` → `assembleRelease bundleRelease`
(unsigned) → artifact upload. No device/emulator tests, no signing key.
Green means "compiles and lints", nothing more.

## 3. GeckoView architecture summary (traced, not assumed)

- **Runtime**: one `GeckoRuntime` per process, held in a `companion object`
  (`MainActivity.kt:110-111`), created once in `onCreate` (`852-857`) from
  `buildRuntimeSettings()` (ETP strict/default, cookie isolation + private-mode
  isolation, query stripping with the LibreWolf lists, Safe Browsing, TRR/DoH,
  HTTPS-only modes, GPC, `pdfjs.disabled`, RFP pref, WebRTC ICE pref,
  `remoteDebuggingEnabled = BuildConfig.DEBUG && store…`,
  crash handler → `GeckoCrashService`). Correct per the GeckoView contract.
- **Sessions**: 1:1 `Tab` ↔ `GeckoSession` (`Tab`, L52-72). `createSession()`
  sets all nine delegates then `open(runtime)`. Closed on every close path
  (`closeTab:1452`, `closeAllTabs:1472`, `suspend:1113`, `onDestroy:2346-2351`,
  refresher:1280-1308). No leak on the close paths.
- **View attach**: single `GeckoView`, `setSession()` on every `selectTab`
  (1413) with `setActive/setFocused` fan-out (1408-1411). Correct.
- **Popups**: `onNewSession` returns an unopened session (never opened/loaded
  by the embedder) — the crash-on-`window.open()` contract is respected (430).
- **Downloads**: `onExternalResponse` → app-owned engine with HTTP Range
  resume; a throwaway session re-fetches authenticated bodies because Gecko
  exposes no cookie read (1264-1311, bounded by `REFRESH_TIMEOUT_MS`). Sound.
- **Recreation**: `runtime` survives activity recreation (static); the `else`
  branch (855-856) re-sets the two activity-bound runtime delegates. The
  `ServiceWorkerDelegate` anonymous object still captures the *first*
  activity — a stale-context leak on the rare recreation path
  (uiMode/locale; rotation is handled via `configChanges`). P3.
- **Process death**: tabs persisted on `onPause`/`onSaveInstanceState`
  (private/home/error/`data:` excluded); restore recreates sessions and
  **loads every URL immediately** (1034-1041). Correct but wasteful (see P2-6).

## 4. Browser lifecycle analysis

| Transition | Behavior | Verdict |
|---|---|---|
| Rotation / keyboard / screenSize | `configChanges` handled; `onConfigurationChanged` re-insets + recomputes PIP params | OK |
| Night-mode / locale change | Activity recreates; runtime reused; tabs rebuilt from store; old sessions closed only in `onDestroy` (runs) but old activity leaks via service-worker delegate | P3 leak, otherwise OK |
| Backgrounding | `onPause` persists tabs | OK |
| Process death | Full restore from store (no back-stack/field state, but tabs+active URL survive) | OK, eager (P2-6) |
| Low memory | `onTrimMemory RUNNING_LOW` clears `ALL_CACHES`; CRITICAL/COMPLETE suspends background sessions; `onLowMemory` suspends | Semantics of ALL_CACHES unverified — see P2-8 |
| Multi-window | No `resizeableActivity=false`; nothing breaks structurally; untested | Unclear, P3 |
| Back | Callback: exit fullscreen → session.goBack → system back | OK |
| Quit | `sanitize()` + `finishAffinity()`; `onDestroy` honors per-category delete-on-quit, closes all sessions | OK, silent-failure caveat P2-9 |
| Cold/warm start | Runtime once per process; restore path correct | OK, eager-load cost P2-6 |
| PIP | Params clamped to platform Rational limits, auto-enter on leave-hint when fullscreen, chrome hidden in PIP | OK |

## 5. Security findings

- **S1 [P1] `intent:` URLs fire with no user confirmation and no
  sanitization.** `openExternalUri` (`MainActivity.kt:1790-1806`):
  `Intent.parseUri(uri, URI_INTENT_SCHEME)` keeps embedded explicit
  `component`, `SEL`, extras and `browser_fallback_url`, then
  `startActivity` with no chooser and no `resolveActivity` check. Reachable
  from web content on any user gesture (`onLoadRequest:370-373`) and from
  typed/pasted text (`navigate:1539-1543`). One tap on a malicious page =
  drive-by app launch / Play Store / dialer / settings. Fix: confirmation
  dialog showing the target, strip `component`/`SEL`/dangerous extras,
  restrict to BROWSABLE resolution.
- **S2 [P1] External input becomes navigation without scheme checks.**
  `EXTRA_NOTIFICATION_URL` (964-968), `intent.data` (990-999 — every
  branch calls `navigate(data)`, the `http`/`data:`/else split is dead
  code), and `openSharedText` (1010-1028, accepts `text/html` shares,
  naive regex tag-strip, loads `data:` payloads from other apps into the
  active tab). Any app on the device can load attacker content in the
  user's session context. Fix: allowlist `http/https` (+ explicit user
  confirm otherwise), validate notification extras, treat share text as
  *search-or-URL only after user confirm* or open in a new tab.
- **S3 [P2] Cross-app triggering of internal actions.** `ACTION_DATA_CLEARED`
  (970-973), `PRIVATE_TAB`, `ABOUT`, extras (`EXTRA_NOTIFICATION_URL`,
  `EXTRA_WEB_APP`) are honored from *any* sender that can start the
  exported `MainActivity` — e.g. any app can wipe engine data or load a URL
  in the user's session. Fix: ignore these actions/extras unless
  `callingPackage == packageName` (or move them to explicit in-app calls).
- **S4 [P1] Passwords to clipboard with no clearing; clipboard auto-read.**
  `fillLoginFromVault` (654-672) and `PasswordsActivity` (88-91) copy
  credentials to the system clipboard indefinitely (label is the *username*
  in one path); `readClipboard` (323-328) runs on every address focus and
  keystroke when suggestions are on (default). Fix: Android 13+ sensitive
  content flag, auto-clear timer, and read clipboard only on explicit
  paste/dedicated suggestion tap.
- **S5 [P1] `Log.d` prints the full page URL** (`MainActivity.kt:723`,
  autofill tag). Fix: remove or gate on `BuildConfig.DEBUG`.
- **S6 [P2] No authentication gate on the password vault UI.** Anyone
  holding the unlocked device opens Passwords and reads every credential
  in clear. Encryption at rest is sound (Keystore AES-256-GCM,
  origin-bound, https-only autofill), but there is no device-credential /
  biometric check before display. Fix: `BiometricPrompt` or
  `KeyguardManager` confirm before `PasswordsActivity` renders.
- **S7 [P2] `file://` / `content://` / `filesystem:` handed to the engine
  (`onLoadRequest:363-366`, subframes always ALLOW). Entirely reliant on
  Gecko's file policy; the embedder adds no check. Fix: DENY or confirm
  non-`http(s)` subframe/top navigations that did not come from user typing.
- **Safe (verified, no action):** no `addJavascriptInterface` /
  `evaluateJavascript` anywhere; file upload uses SAF + FileProvider temp
  files; FileProvider non-exported with per-intent read grants (scope is
  broad — whole `cache-path` + public `Download/` — but grants are
  per-intent; narrow to subpaths in Phase C); cleartext blocked in
  `network_security_config.xml` with no cert-bypass code; no hardcoded
  secrets/keys (keystore stays outside the repo, gitignored, only the
  alias is logged); `allowBackup=false`, no backup rules; only
  `MainActivity` exported; FGS types match manifest; all dangerous
  permissions go through runtime request.
- **Over-privilege [P3]:** `ACCESS_NETWORK_STATE` declared, zero usages —
  remove.

## 6. Performance findings

- **P1 Tray rebind storm.** Every `onProgressChange` tick (up to ~100 per
  page load), title change, location change, page start/stop calls
  `tabsSheet?.refresh(snapshot(), …)` → `paint()` re-sets the
  `LayoutManager`, re-sets the adapter and `notifyDataSetChanged()` — even
  when the sheet is hidden. `snapshot()` allocates per call plus favicon
  lookups. Visible symptom beyond CPU: opening the tray mid-load resets
  scroll. Lint flags all 9 `notifyDataSetChanged` sites. Fix: DiffUtil (or
  at minimum targeted notifies), skip `paint()` when the sheet is not
  visible, throttle progress-driven refresh to active-tab progress bar
  only.
- **P1 Suggestions parse stores per keystroke.** `updateSuggestions`
  calls `historyStore.all()` twice + `bookmarkStore.all()` once per
  keystroke on the main thread — each is a full SharedPreferences JSON
  parse (history capped at 300). Fix: in-memory cached lists invalidated
  on write, plus debounce.
- **P2 Eager tab restore.** All restored tabs get a session + `loadUri`
  at startup. Fix: restore metadata for all, load only the active tab,
  mark the rest lazy (load on first select).
- **P2 Unbounded site-image decode (also a security-adjacent crash
  vector).** `FaviconCache.fetch` → `BitmapFactory.decodeStream` with no
  `inSampleSize`/dimension cap; `prefetchImage` fetches *arbitrary
  manifest icon URLs*. A hostile giant image = OOM. Fix: bound decode
  (`inJustDecodeBounds` + sample size, max dimension), cap `images` with
  LRU instead of `clear()`-at-20, same for `cache` (clear-at-100
  thrashes).
- **P3 Small leaks/churn:** `misses`/`imageMisses` sets grow forever;
  `paint()` re-scales bitmaps per bind instead of caching the scaled
  result; `store.update()` + `publishProgress()` per 500 ms checkpoint
  re-serializes the whole download list (acceptable, leave unless
  profiling says otherwise); ~40 `PrivacyStore` setters are fine
  (prefs are in-memory).
- **No issue found:** single runtime per process (no repeated init);
  sessions closed on all close paths; downloads on daemon pool threads;
  no coroutines to leak; no WebView; logging volume low except S5.

## 7. Dependency findings

Five dependencies, all used, no removal candidate:

| Dependency | Pinned | Lint says | Assessment |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.13.1 | 1.19.1 available | Safe to defer; no known vuln touching used APIs |
| `androidx.appcompat:appcompat` | 1.7.0 | 1.8.0 available | Defer |
| `com.google.android.material:material` | 1.12.0 | 1.14.0 available | Defer |
| `androidx.recyclerview:recyclerview` | 1.3.2 | 1.4.0 available | Defer (needed for DiffUtil fix — API stable) |
| `org.mozilla.geckoview:geckoview` | 147.0.20260212191108 | — | Pinned deliberately (last AAR targeting compileSdk 37). Do not touch until SDK 37.1 is stable |
| AGP 9.4.1 / Gradle 9.7.1 / JDK 17 / kotlin-stdlib forced 2.2.20 | — | — | Current; the stdlib `force` is load-bearing (AGP 9.4 metadata), do not remove |

No analytics/crash/networking/image/DI/JSON libraries — platform
`org.json` + `HttpURLConnection` throughout. **Do not mass-upgrade.**
Upgrade only appcompat/material if a specific bug demands it, one at a
time, with a device run after each.

## 8. Feature inventory

### Working (traced end to end in code)
Tabs + private tabs (isolated, never persisted/restored); session restore
(normal tabs); popup blocking + `window.open` contract; per-site permissions
(site prompt → Android runtime → remembered, per-capability lists, engine +
shell reset); ETP strict/default, cookie isolation, query stripping with
LibreWolf lists, HTTPS-only incl. strict, DoH picker, GPC, Safe Browsing
wiring; downloads (pause/resume via Range, retry, safety warnings,
foreground service + notification); history/bookmarks/password-vault CRUD
with JSON import/export; Keystore vault + origin-bound https-only autofill;
find-in-page; JS dialogs/auth/`beforeunload`; file/camera upload via SAF;
fullscreen + PIP (aspect-clamped, auto-enter on leave-hint); MediaSession +
notification transport; web-app install/shortcuts/display-mode; share-in
(text/HTML/subject/multi); local error pages for network/TLS/SB blocks;
per-ABI splits; R8 release with verified keeps; onboarding; diagnostics;
12-category searchable settings; per-category delete-on-quit + quit flow.

### Partially working
- Session restore (loads everything eagerly; duplicate-URL active-tab match
  can select the wrong tab) — P2-6 + P3.
- `suspendBackgroundTabs` budget arithmetic (`budget = size - keep`) does
  not read as "keep 3"; observed harmless per TESTING.md but unverified
  which tabs suspend — needs a test, P2-7.
- Clipboard suggestions (works, but auto-reads — S4).
- Recently-closed (recorded, no UI reads it back) — missing UI, P2.

### Broken (confirmed by trace)
- Nothing that crashes on the exercised paths. The two manifest receivers
  flagged mid-audit turned out to be **correct**: both receivers are
  top-level classes in their files
  (`PrivateBrowsingNotifier.kt:172`,
  `BrowserMediaController.kt:471`), so the manifest names resolve and the
  notification actions deliver. An earlier suspicion of mis-declaration
  was checked and withdrawn.

### Missing (production-relevant)
- Recently-closed-tabs UI (data exists in `BrowserSessionStore`). P2.
- Vault authentication gate (S6). P2.
- Monochrome themed launcher icon (lint `MonochromeLauncherIcon` ×2). P3.
- Localization: EN only, 598 strings, no other `values-*` locale. P3.
- Preference schema version / migration hook (tolerant `opt*` reads only).
  P2 before the first breaking format change.
- `SEND_MULTIPLE` manifest filter absent though `openSharedText` handles
  it. P3.
- No in-app updater (documented as intentional). Not rated.

### Unclear (needs a device run, do not assert)
- Real Safe Browsing verdict firing; threat pill/count UI.
- Private-browsing notification posting end to end.
- `ALL_CACHES` semantics on trim (cookies included? → random logouts).
- Multi-window behavior; RTL layout; bookmark-list favicons; per-origin
  reset against a real site; transient audio-focus loss; tab-suspend +
  revive round trip; voice-search on devices without a recognizer
  (`startVoiceSearch` has no availability guard — check before release).
- R8 behavior of the wrong `BasicSelectionActionDelegate` keep (points at
  a non-existent app class; engine class covered by consumer rules —
  almost certainly harmless, verify release logcat once).

## 9. Bugs discovered (in code, not yet device-confirmed where noted)

1. `handleIntent` `http`/`data:`/else branches all call `navigate(data)`
   (990-999) — dead branching, and the real issue is S2. P1 via S2.
2. `ACTION_PRIVATE_TAB` if/else identical branches (979-982). Dead. P3.
3. Tray scroll reset + rebind storm (§6-P1).
4. `closeTab` records error pages and `data:` URLs into recently-closed
   while `saveTabState` filters them — inconsistent. P3.
5. `restoreTabsIfAvailable` matches the active tab by URL equality —
   duplicate URLs restore focus to the wrong tab. P3 (fix: persist active
   tab id).
6. `eraseEngineData` swallows all exceptions (116-121) — a failed wipe
   reports success for a privacy promise. P2 (surface failures to the
   settings UI).
7. `openExternalUri` has no `resolveActivity`/chooser and launches
   `intent:` verbatim. P1 via S1.

## 10. Technical debt (evidence, not a work order)

- `MainActivity` 2,373 lines; only `onCreate` exceeds ~120 lines. Extract
  helpers per area; do not split the class for its own sake.
- Duplicated: store JSON read/write ×5 stores; `AlertDialog` builders
  (~20 sites); notification-permission flow in Onboarding vs Settings;
  FileProvider/`Download/` branching ×4; `runCatching{Uri.parse…host}`
  host helper ×7 — extract one `hostOf()`.
- Dead (zero refs confirmed): `KeystoreAes` object (LoginStore has its own
  impl; its comment even describes a non-existent sync identity),
  `Format.duration()`, `DisplayRate.release()`, drawables
  `bg_address/bg_card_selected/ic_folder/ic_forward/ic_lock/ic_mask`,
  menus `browser_menu/menu_main`, ~15 dead strings incl.
  `settings_account/settings_general/settings_language/settings_dl/
  settings_lna`, time-range variants; lint adds 53 `UnusedResources`
  entries, 38 `UseKtx`, 18 hardcoded-text/`SetTextI18n`, 10
  `ObsoleteSdkInt`, 17 `Overdraw`, 20 `ContentDescription`, 2
  `PluralsCandidate`, 1 `RtlSymmetry`, 1 `Autofill`.
- ProGuard: keep for non-existent
  `com.palash.librewolfandroid.BasicSelectionActionDelegate` (real class
  is `org.mozilla.geckoview.*`, covered by consumer rules) — fix the
  line; `-keepclassmembers …** { <fields>; }` keeps every field
  package-wide (narrow to the JSON-persisted stores when touching);
  relying on GeckoView consumer rules is correct, keep the comment.
- Naming/typos: `MICROMIUM_DESKTOP_UA`, `about_librefox_release`,
  cryptic `settings_dl/settings_lna`, `clipboard_suggestions` vs
  `settings_clipboard_suggestions`.
- Comment drift: `KeystoreAes` sync comment; `LibreWolfDefaults` autoplay
  comment vs exposed autoplay settings; `SystemBars` status-bar comment
  vs themes still setting the colors.
- Fragile: 2× `!!`, several unchecked `as` casts in adapters/delegates,
  ~30 swallowed `catch(_:Exception)` / bare `runCatching` (tolerable in
  delegate callbacks, audit the data-loss paths only), all-async
  `apply()` with no barrier where ordering matters (session save vs
  process kill — acceptable, but `onDestroy` save-then-die is best-effort).
- `ACCESS_NETWORK_STATE` declared, unused — delete.
- Hardcoded divider colors (`#3A3A40`, `#2A2A30`) across 9 layouts;
  `dimens.xml` holds only `favicon_size`; inline CSS colors in
  `BrowserErrorPage`; hardcoded GitHub URLs in 3 places (fine, but move
  to `LibreWolfDefaults`).

## 11. Missing production features (prioritized in §12)

P2: recently-closed UI, vault auth gate, prefs schema version.
P3: monochrome icon, localization groundwork, `SEND_MULTIPLE` filter,
accessibility descriptions (20 lint hits), per-site storage sizes and
other engine-capped items already documented in `docs/SETTINGS-PLAN.md`
— do not re-litigate those here.

## 12. P0/P1/P2/P3 list

**P0 — none found.** The app launches, browses, persists, upgrades and
passes lint with 0 errors. There is no crash-on-start, no broken
signing/CI, no data-loss-on-launch path in the traced code. This matters:
the release is not blocked by an unknown catastrophe; it is blocked by
the sharp edges below.

**P1 — fix before any release calling itself production:**
1. `intent:` external launch without confirm/sanitization (S1).
2. External/share/notification input → navigation without scheme gating
   (S2) + dead branches in `handleIntent`.
3. Clipboard credential handling + auto-read (S4).
4. Page URL to logcat (S5).
5. Favicon/manifest-image unbounded decode → OOM ( §6-P2 ).
6. Tray rebind storm + scroll reset (§6-P1) — also the 9 lint hits.
7. Suggestions triple store-parse per keystroke (§6-P1).

**P2 — significant, schedule immediately after:**
8. Third-party triggering of `DATA_CLEARED`/private-tab/URL-load via the
   exported activity (S3).
9. Vault with no auth gate (S6).
10. Eager restore of all tabs (restore metadata, load lazily).
11. Silent wipe failures in `eraseEngineData` (surface them).
12. `file://`/`content://` to engine without embedder check (S7).
13. Recently-closed UI (data already collected).
14. Suspension-budget arithmetic unverified (write the test).
15. `ALL_CACHES`-on-trim semantics unverified (check for cookie wipe).
16. Prefs schema version before any breaking format change.
17. ProGuard wrong keep line + overbroad field keep (verify release
   logcat once while touching).

**P3 — polish/debt (batch, low risk):**
18. Dead code + 53 unused resources + ~15 dead strings + typos.
19. `UseKtx`/hardcoded text/`SetTextI18n`/`ObsoleteSdkInt`/`Overdraw`/
   `ContentDescription` lint batches.
20. Helper extraction (hostOf, dialog builders, store base).
21. ServiceWorkerDelegate stale-context note; favicon LRU; misses-set
   growth; closeTab/recently-closed filter parity; active-tab-by-id;
   `SEND_MULTIPLE` filter; divider colors → resources; monochrome icon;
   RTL pass; multi-window smoke test; voice-search availability guard.

## 13. Recommended implementation order

- **Phase A — user safety (P1 security, small diffs, each device-verified
  individually):** A1 external-launch confirm + `intent:` sanitization.
  A2 navigation allowlist (`http/https` default, confirm the rest) +
  notification/share sender checks (also fixes S3). A3 clipboard:
  sensitive-flag + auto-clear + explicit-read. A4 remove URL log.
  A5 bounded image decode + LRU. Each is a contained change with a
  before/after device check; do not batch them into one commit.
- **Phase B — responsiveness (P1/P2 perf):** B1 tray DiffUtil + refresh
  only when visible + progress bar decoupled from tray refresh. B2
  suggestion-store caching + debounce. B3 lazy tab restore. B4 verify
  suspension budget + trim semantics with tests/runs.
- **Phase C — trust surfaces (P2):** C1 vault auth gate. C2 wipe-failure
  surfacing. C3 recently-closed UI on the existing store. C4 prefs schema
  version. C5 ProGuard keep correction + release-logcat verification.
- **Phase D — debt sweep (P3, mechanical):** dead code/resources/strings,
  typos, lint batches, helper extraction, resources for hardcoded values,
  monochrome icon, `SEND_MULTIPLE`, RTL/multi-window/voice guards.
- **Explicitly out of scope:** engine upgrade (GeckoView 147 stays until
  SDK 37.1 is stable), architecture rewrite (no ViewModels/DI/Compose),
  mass dependency upgrades, visual redesign, new features beyond the P2
  list, in-app updater.

Acceptance for leaving this phase: this file exists, the tree builds
(`assembleDebug` + `lintDebug` green on 2026-10-05), and no code was
changed — Phases A–D are separate, individually verified steps.
