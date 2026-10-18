# Running the automated test suite

Three layers, smallest reliable layer first. Everything here has been executed;
the recorded outcomes are in the Phase 9 report, not invented.

## Prerequisites

| | |
|---|---|
| JDK | 17 or newer (CI uses 17; local builds have used 25) |
| Android SDK | `compileSdk 37`, build-tools installed |
| Device | arm64-v8a, Android 8.0+ for instrumentation |
| Network | Only to resolve Gradle dependencies and GeckoView. **Tests do not use the internet.** |

Set `JAVA_HOME` and `ANDROID_HOME` before running Gradle.

---

## 1. Unit tests — JVM, no device

```bash
./gradlew :app:testDebugUnitTest
```

32 tests across two classes, both pure logic with no Android framework
dependency on the paths exercised:

- `DownloadSafetyTest` (19) — executable/MIME/double-extension/cleartext
  classification and filename sanitisation, including path traversal.
- `HistoryStoreTest` (13) — ordering, URL re-add deduplication, the 300-entry
  retention boundary, corrupt and wrong-typed stored JSON, unicode, zero
  timestamps, full replacement.

`HistoryStoreTest` drives `readFromDisk`/`writeToDisk` against an in-memory
`SharedPreferences` fake, so the 300-entry case needs no device, no `run-as`
and no debuggable build, and it cannot touch a real user's history.

`org.json` is an Android framework stub that throws in unit tests, so a real
implementation is on the test classpath. Do **not** "fix" this with
`testOptions.unitResults.returnDefaultValues` — every call would then return
0/null and the history tests would pass against empty results.

Results: `app/build/reports/tests/testDebugUnitTest/index.html`

---

## 2. Instrumentation tests — real device or emulator

```bash
./gradlew :app:connectedDebugAndroidTest
```

15 tests that drive the real app with a real GeckoView:

- `BrowserNavigationTest` (8) — load, second page, back, redirect following,
  one request per navigation, an HTTP error page, a malformed URL, and rapid
  navigation resolving to the last requested page.
- `TabsAndHistoryTest` (7) — new tab, several tabs, rotation preserving the
  count, background/return, a page reaching history, dedup on repeat visits,
  and clear-history.

### No public internet

`FixtureServer` is a loopback HTTP server written on a raw `ServerSocket`,
started per test. It serves `/page1..3`, `/redirect`, `/status/<code>`,
`/slow` and `/big`, with fixed titles and fixed markup. A test failure
therefore means this app did something wrong, not that a website was slow or
changed. No third-party site, no tracking script, no secret.

### Rules the harness follows

These are not style preferences; each one exists because breaking it produced a
wrong answer earlier.

- **Synchronise on a real signal.** `Ui.waitForTitle` waits for the address bar
  to show the loaded page title, with a bounded timeout. A fixed sleep cannot
  distinguish a slow page from a failed one.
- **Parse structurally.** Elements are found by resource-id through
  `UiDevice.wait(Until.findObject(...))`. Nothing assumes a fixed attribute
  order or matches on coordinates or translated display text.
- **Clear the address field before typing.** `Ui.clearAddressField` moves to
  the end and deletes, then `navigate` reads the field back and **fails** if it
  does not hold exactly the intended URL. The earlier shell harness appended
  instead, which turned every URL after the first into a nonsense host that
  resolved to an error page and was then measured as a successful load.
- **Never force-stop the app from a test.** The instrumentation APK shares the
  target package, so `am force-stop` kills the test process itself: the run ends
  instantly with "Process crashed", an empty `<failure/>` and a ~0.01 s
  duration. Determinism comes from explicit preconditions instead.
- **BACK only while the sheet is confirmed open.** The app closes the tray by
  itself after a new tab is created; an unconditional BACK then exits to the
  launcher and the next assertion fails with the launcher reported as the
  foreground package.
- **Tab count comes from the toolbar badge's accessibility label**, not from
  tray rows. The tray is a viewport: past a few tabs its row count is not a tab
  count. Nothing here taps `tray_close_all`, whose label is "Close all N tabs".
- **Onboarding is skipped through the app's own accessor**
  (`PrivacyStore.onboardingComplete`), not by tapping through it, and without
  adding a test-only branch to production code.

Results:
- `app/build/reports/androidTests/connected/<device>/index.html`
- `app/build/outputs/androidTest-results/connected/<device>/` — XML, plus a
  per-test logcat capture that is attached on failure.

### CI

Instrumentation is a **manual** job in `.github/workflows/android.yml`
(`workflow_dispatch` only). It is not on the automatic push path: these tests
take minutes, and the emulator job has not been observed running against this
repository from CI, so promoting it without evidence would add an unverified
gate. Until it has been seen green once, the local run above is authoritative.

---

## 3. Static analysis and builds

```bash
./gradlew :app:lintDebug      # 0 errors
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

`assembleRelease` is not optional. R8 breakage is invisible to a debug build:
a stripped or renamed GeckoView callback only shows up in a minified build
running on a device.

Release builds unsigned unless `keystore.properties` exists, which is
gitignored. CI therefore builds unsigned and signs nothing.

---

## Full local gate

```bash
./gradlew :app:testDebugUnitTest \
          :app:lintDebug \
          :app:assembleDebug \
          :app:assembleRelease \
          :app:bundleRelease
./gradlew :app:connectedDebugAndroidTest     # needs a device
```

Any failure fails the build. There is no `|| true`, no
`continue-on-error`, and no blanket exception handling in the workflow.

---

## Known environmental constraints

- **Instrumentation needs a device.** There is no emulator configured on this
  machine, so `connectedAndroidTest` has only been run against physical
  hardware. A missing device is a **blocked** gate, never a pass.
- **`adb shell am instrument` is killed if the adb shell session dies.** Run
  instrumentation through Gradle, or keep the session alive; a truncated run
  looks like a passing run otherwise.
- **Some vivo devices report `DELETE_FAILED_INTERNAL_ERROR` from `pm uninstall`
  while actually removing the package.** Check `pm list packages` before
  concluding an uninstall failed.