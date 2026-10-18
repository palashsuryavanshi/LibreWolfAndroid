package com.palash.librewolfandroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tab lifecycle and persistence regressions.
 *
 * The tab count is read from the toolbar badge's own accessibility label, not
 * from the tray. The tray is a viewport: with more tabs than fit, its row
 * count is a number of rows, not a number of tabs, and a test that trusts it
 * will quietly pass for the wrong reason. A related trap is the tray's
 * close-all control, whose label is "Close all N tabs": any helper that
 * dismisses the sheet by matching text can wipe the entire session. Nothing
 * here taps it.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TabsAndHistoryTest {

    private lateinit var server: FixtureServer

    @Before
    fun setUp() {
        server = FixtureServer()
        // No force-stop: the instrumentation shares this package name, so
        // stopping the package stops the test itself. Determinism comes from
        // explicit preconditions instead -- a cleared history store and tab
        // counts read relative to the start of the test.
        runCatching { Ui.historyStore().clear() }
        assertTrue("app did not reach its main screen", Ui.launchBrowser())
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    /**
 * Open the tray and press its New tab control, wherever it currently is.
 *
 * BACK is only ever pressed while the sheet is CONFIRMED open. The app closes
 * the tray by itself once a tab has been created, so an unconditional BACK
 * after tapping New tab exits the browser to the launcher -- and the next
     * assertion then fails with "no view with resource-id 'tabs_count'" while
     * reporting the launcher as the foreground package. That is a failure in
     * the test's own driving, not in the app.
     */
    private fun openNewTab(): Boolean {
        Ui.tap("tabs_count")
        Thread.sleep(1_500)
        val fab = Ui.find("sheet_new_tab", 5_000) ?: return false
        fab.click()
        Thread.sleep(1_800)
        if (Ui.find("sheet_new_tab", 1_500) != null) {
            Ui.back()      // the sheet is still up: dismiss it
            Thread.sleep(600)
        }
        return Ui.tabCount() >= 0
    }

    @Test
    fun newTabIncreasesTheCount() {
        val before = Ui.tabCount()
        assertTrue("could not read the tab count", before >= 0)
        assertTrue("could not open a new tab", openNewTab())
        val after = Ui.tabCount()
        assertEquals("one new tab should have been added", before + 1, after)
    }

    @Test
    fun severalTabsOpenAndAllRemainReachable() {
        val before = Ui.tabCount()
        repeat(3) { assertTrue("tab ${it + 1} did not open", openNewTab()) }
        val after = Ui.tabCount()
        assertEquals("three new tabs should have been added", before + 3, after)
    }

    @Test
    fun rotationPreservesTheTabCount() {
        repeat(3) { openNewTab() }
        val before = Ui.tabCount()

        val device = androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        )
        device.setOrientationLeft()
        Thread.sleep(3_000)
        val landscape = Ui.tabCount()
        device.setOrientationNatural()
        Thread.sleep(3_000)
        val portrait = Ui.tabCount()

        assertEquals("tab count changed in landscape", before, landscape)
        assertEquals("tab count changed returning to portrait", before, portrait)
        assertTrue("browser died on rotation", Ui.isAppForeground())
    }

    @Test
    fun backgroundAndReturnKeepsTheSession() {
        repeat(2) { openNewTab() }
        val before = Ui.tabCount()
        Ui.home()
        Thread.sleep(3_000)
        Ui.relaunch()
        assertTrue("browser did not come back", Ui.waitForAddressBar())
        assertEquals("tab count changed across backgrounding", before, Ui.tabCount())
    }

    @Test
    fun navigatedPageIsRecordedInHistory() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")

        // The app's own store, reached the way the app reaches it. The write is
        // now asynchronous by design, so a bounded wait is required -- and a
        // fixed sleep would be wrong here because the wait is for a
        // condition, not for time.
        val store = Ui.historyStore()
        val deadline = System.currentTimeMillis() + 15_000
        var urls = emptyList<HistoryEntry>()
        while (System.currentTimeMillis() < deadline) {
            urls = store.all()
            if (urls.any { it.url.contains("/page1") }) break
            Thread.sleep(250)
        }
        assertTrue(
            "expected the loaded page in history, got ${urls.map { it.url }}",
            urls.any { it.url.contains("/page1") },
        )
        assertNotNull("history entry should carry a title", urls.first().title.ifBlank { null })
    }

    @Test
    fun historyDeduplicatesRepeatedVisitsToTheSameUrl() {
        val url = server.url("/page2")
        Ui.navigate(url)
        Ui.waitForTitle("Fixture Page 2")
        Ui.navigate(url)
        Ui.waitForTitle("Fixture Page 2")
        Thread.sleep(2_500)

        val store = Ui.historyStore()
        val entries = store.all().filter { it.url.contains("/page2") }
        assertEquals(
            "the same URL should occupy one history slot, not several",
            1, entries.size,
        )
    }

    @Test
    fun clearingHistoryEmptiesTheStore() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        Thread.sleep(1_500)

        val store = Ui.historyStore()
        assertTrue("precondition: history should have an entry", store.all().isNotEmpty())

        store.clear()

        // Bounded wait for the asynchronous write rather than an immediate
        // read: clear() updates memory immediately and persists in the
        // background, and a later queued write must not resurrect the history.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && store.all().isNotEmpty()) {
            Thread.sleep(250)
        }
        assertEquals("history should be empty after clear", 0, store.all().size)
    }
}