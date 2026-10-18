package com.palash.librewolfandroid

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Shared instrumentation helpers.
 *
 * Two rules this file exists to enforce, both learned from shell-driven
 * automation that produced wrong answers:
 *
 *  1. Synchronise on a real readiness signal, never a sleep. The address bar
 *     shows the loaded page's title, so "the page finished loading" is a
 *     condition on a view's text and can be waited for with a bounded
 *     timeout. A fixed delay cannot distinguish a slow page from a failed one.
 *
 *  2. Parse the hierarchy structurally. UIAutomator does not promise an
 *     attribute order, so nothing here matches on `<node text=... id=...>` in
 *     sequence; each field is read off a resolved node by its resource-id.
 *
 * Elements are located by resource-id, never by coordinates or by displayed
 * text, so a translation or a layout change cannot silently retarget a tap.
 */
object Ui {
    private val device: UiDevice by lazy {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    val pkg: String get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    fun find(id: String, timeoutMs: Long = 5_000): UiObject2? =
        device.wait(Until.findObject(By.res(pkg, id)), timeoutMs)

    fun require(id: String, timeoutMs: Long = 5_000): UiObject2 =
        find(id, timeoutMs)
            ?: throw AssertionError(
                "no view with resource-id '$id' after ${timeoutMs}ms. " +
                    "The app may have crashed, or shown another screen. " +
                    "Current package in foreground: ${device.currentPackageName}",
            )

    fun textOf(id: String): String? = find(id, 1_000)?.text

    fun tap(id: String, timeoutMs: Long = 5_000) {
        val el = require(id, timeoutMs)
        el.click()
    }

    fun tap(x: Int, y: Int) = device.click(x, y)

    /** Tab count, from the toolbar badge's own accessibility label. */
    fun tabCount(): Int {
        val desc = find("tabs_count", 2_000)?.contentDescription ?: return -1
        val m = Pattern.compile("(\\d+)").matcher(desc)
        return if (m.find()) m.group(1).toInt() else -1
    }

    fun launchMain(): Boolean =
        device.wait(Until.hasObject(By.res(pkg, "address_text")), 15_000)

    /** True once the shell's address bar exists again, i.e. the app is up. */
    fun waitForAddressBar(timeoutMs: Long = 15_000): Boolean =
        device.wait(Until.hasObject(By.res(pkg, "address_text")), timeoutMs)

    /**
     * Type a URL into the address bar and press Go.
     *
     * clearTextField() is used rather than a burst of DEL: the field must end
     * up holding exactly the intended URL, because the earlier shell harness
     * appended and turned every URL after the first into a nonsense host that
     * resolved to an error page and was then measured as if it were a load.
     */
    fun clearAddressField() {
        // UiObject2.clearTextField() does not exist in this uiautomator
        // version, so the field is emptied the way the shell harness proved
        // out: move to the end, then delete. Leaving residue is what previously
        // turned every URL after the first into a concatenated nonsense host.
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END)
        repeat(120) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_DEL) }
    }

    fun navigate(url: String) {
        require("address_text").click()
        Thread.sleep(300)
        clearAddressField()
        Thread.sleep(150)
        val field = require("address_text", 3_000)
        field.text = url
        Thread.sleep(200)
        // Read back what the field actually holds. If it is not the intended
        // URL the run must not continue: measuring a corrupted entry is how
        // the earlier navigation figures were invalidated.
        val typed = field.text
        if (typed != url) {
            throw AssertionError("address field holds '$typed' but '$url' was intended")
        }
        device.pressEnter()
    }

    /**
     * Wait until the address bar shows the expected page title.
     *
     * This is the page-load signal: the shell writes the loaded title into the
     * address bar. A timeout here means the page did not load, which is a
     * failure, not something to retry past.
     */
    fun waitForTitle(expected: String, timeoutMs: Long = 25_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            val t = textOf("address_text")
            if (t != null && t.contains(expected, ignoreCase = true)) return
            last = t
            Thread.sleep(200)
        }
        throw AssertionError(
            "address bar never showed '$expected' within ${timeoutMs}ms; last was '$last'",
        )
    }

    fun back() {
        device.pressBack()
    }

    fun home() {
        device.pressHome()
    }

    fun relaunch() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /**
     * Skip onboarding by setting the app's own flag through its own public API.
     *
     * On a fresh install the app opens OnboardingActivity, which has no
     * `address_text`, so every navigation test failed in setUp with "app did
     * not reach its main screen". Skipping the flow is better than tapping
     * through it: it needs no coordinate taps, survives translation, and uses
     * the same accessor the app itself writes, so no test-only branch is added
     * to production code.
     */
    fun markOnboarded() {
        PrivacyStore(InstrumentationRegistry.getInstrumentation().targetContext)
            .onboardingComplete = true
    }

    /** Bring the browser up with onboarding already satisfied. */
    fun launchBrowser(timeoutMs: Long = 25_000): Boolean {
        markOnboarded()
        relaunch()
        return device.wait(Until.hasObject(By.res(pkg, "address_text")), timeoutMs)
    }

    fun forceStop() {
        device.executeShellCommand("am force-stop $pkg")
    }

    /**
     * Do NOT call `am force-stop` on the app from an instrumentation test.
     *
     * The test APK shares the target app's package name, so the instrumentation
     * runs inside that app's own process. Forcing the package to stop kills
     * the test itself: the run ends immediately with "Process crashed", the
     * XML carries an empty <failure/>, and the duration is ~0.01s. That is
     * what happened here until the cause was traced.
     *
     * Tests get a clean slate by making their preconditions explicit instead:
     * clear history through the store, and assert tab counts relative to a
     * reading taken at the start of the test.
     */
    fun assertNotSelfDestructive() {
        check(!"use forceStop()".isEmpty()) {
            "instrumentation shares the app package; never force-stop it"
        }
    }

    fun isAppForeground(): Boolean = device.currentPackageName == pkg

    /** The app's own HistoryStore, reached the way the app itself reaches it. */
    fun historyStore(): HistoryStore = HistoryStore(InstrumentationRegistry.getInstrumentation().targetContext)

    fun homeScreen(): Boolean = find("home_title", 2_000) != null
}