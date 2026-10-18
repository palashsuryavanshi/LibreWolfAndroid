package com.palash.librewolfandroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Private browsing isolation — the Phase 6 requirement that a private session
 * leaves no trace in the regular history store.
 *
 * This is the check that matters most in this codebase and was previously only
 * performed by hand. Isolation here is not a UI convention: the same
 * `HistoryStore` backs both, and the only thing keeping a private visit out of
 * it is the guard at the call site in `onPageStop`. A regression that dropped
 * that guard would be invisible in the browser UI — the page would render
 * perfectly — and would only show up in the store, which is what this asserts.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PrivateBrowsingIsolationTest {

    private lateinit var server: FixtureServer

    @Before
    fun setUp() {
        server = FixtureServer()
        // Clear through the store rather than force-stopping: the test APK
        // shares this package, so stopping the package stops the test.
        runCatching { Ui.historyStore().clear() }
        assertTrue("app did not start", Ui.launchBrowser())
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    /**
     * Open a private tab: tray -> Private chip -> New tab.
     *
     * The tray passes `mode == MODE_PRIVATE` to `onNewTab`, so the FAB creates
     * a private tab only while the Private chip is the selected mode. BACK is
     * pressed only while the sheet is confirmed open, because the sheet closes
     * itself once the tab exists and an unconditional BACK exits the browser.
     */
    private fun openPrivateTab(): Boolean {
        Ui.tap("tabs_count")
        Thread.sleep(1_500)
        val chip = Ui.find("mode_private", 5_000) ?: return false
        chip.click()
        Thread.sleep(1_000)
        val fab = Ui.find("sheet_new_tab", 5_000) ?: return false
        fab.click()
        Thread.sleep(2_000)
        if (Ui.find("sheet_new_tab", 1_500) != null) {
            Ui.back()
            Thread.sleep(600)
        }
        return Ui.isAppForeground()
    }

    private fun historyUrls(): List<String> = Ui.historyStore().all().map { it.url }

    private fun awaitHistory(urlFragment: String, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (historyUrls().any { it.contains(urlFragment) }) return true
            Thread.sleep(250)
        }
        return false
    }

    @Test
    fun regularNavigationIsRecordedInHistory() {
        // Precondition for the isolation claim below: the normal path must
        // actually write, otherwise "not present" proves nothing.
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        assertTrue(
            "a normal visit should be recorded; history=${historyUrls()}",
            awaitHistory("/page1"),
        )
    }

    @Test
    fun privateNavigationIsNotRecordedInRegularHistory() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        assertTrue("precondition: normal history should hold /page1", awaitHistory("/page1"))

        assertTrue("could not open a private tab", openPrivateTab())

        // Navigate inside the private tab.
        Ui.navigate(server.url("/page2"))
        Ui.waitForTitle("Fixture Page 2")

        // Give any wrong write the same settling time the correct write was
        // given above, so the comparison is fair rather than a race the
        // isolation only wins by being slower.
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            if (historyUrls().any { it.contains("/page2") }) break
            Thread.sleep(250)
        }

        val leaked = historyUrls().filter { it.contains("/page2") }
        assertTrue(
            "private visit to /page2 leaked into regular history: $leaked",
            leaked.isEmpty(),
        )
        assertTrue(
            "the normal visit should still be present, proving the store works",
            historyUrls().any { it.contains("/page1") },
        )
    }

    @Test
    fun privateVisitIsAbsentEvenAfterRelaunch() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        assertTrue("precondition", awaitHistory("/page1"))

        assertTrue("could not open a private tab", openPrivateTab())
        Ui.navigate(server.url("/page2"))
        Ui.waitForTitle("Fixture Page 2")
        Thread.sleep(3_000)

        // Restart the shell and re-check: the session store is written on
        // pause/stop, so a leak that only appeared on the way out would be
        // caught here rather than being mistaken for correct behaviour.
        Ui.relaunch()
        assertTrue("app did not restart", Ui.launchBrowser())
        Thread.sleep(2_500)

        assertFalse(
            "private URL persisted across a restart: ${historyUrls()}",
            historyUrls().any { it.contains("/page2") },
        )
    }

    @Test
    fun aPrivateTabDoesNotDisturbExistingNormalHistory() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        assertTrue("precondition", awaitHistory("/page1"))
        val before = historyUrls()

        assertTrue("could not open a private tab", openPrivateTab())
        Ui.navigate(server.url("/page3"))
        Ui.waitForTitle("Fixture Page 3")
        Thread.sleep(3_000)

        val after = historyUrls()
        assertTrue(
            "the pre-existing normal entry was removed: before=$before after=$after",
            after.containsAll(before),
        )
    }
}