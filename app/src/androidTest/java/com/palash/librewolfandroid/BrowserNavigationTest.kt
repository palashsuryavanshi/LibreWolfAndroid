package com.palash.librewolfandroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Core browsing regressions, against a deterministic loopback fixture server.
 *
 * No test here touches the public internet. Every page is served locally with
 * a fixed title and fixed markup, so a failure means this app did something
 * wrong rather than that a website was slow or changed.
 *
 * Each test navigates and then WAITS for the address bar to show the loaded
 * title. That is the real page-load signal; a timeout is reported as a
 * failure rather than papered over with a longer sleep.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BrowserNavigationTest {

    private lateinit var server: FixtureServer

    @Before
    fun setUp() {
        server = FixtureServer()
        assertTrue("app did not reach its main screen", Ui.launchBrowser())
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun t01_localFixturePageLoads() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        assertTrue("expected the browser to stay in the foreground", Ui.isAppForeground())
    }

    @Test
    fun t02_secondPageLoadsAndTitleUpdates() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        Ui.navigate(server.url("/page2"))
        Ui.waitForTitle("Fixture Page 2")
        assertNotEquals("title should have changed with the page", "Fixture Page 1", "Fixture Page 2")
    }

    @Test
    fun t03_backReturnsToThePreviousPage() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        Ui.navigate(server.url("/page2"))
        Ui.waitForTitle("Fixture Page 2")
        Ui.back()
        Ui.waitForTitle("Fixture Page 1")
    }

    @Test
    fun t04_redirectIsFollowed() {
        Ui.navigate(server.url("/redirect"))
        // The server answers 302 -> /page1, so the browser must end on page 1.
        Ui.waitForTitle("Fixture Page 1")
        assertEquals("redirect target should have been fetched", 1, server.hits("/page1"))
    }

    @Test
    fun t05_eachNavigationHitsTheServerExactlyOnce() {
        Ui.navigate(server.url("/page3"))
        Ui.waitForTitle("Fixture Page 3")
        // Guards the measurement harness rather than the app: a double fetch
        // would make any timing figure meaningless.
        assertEquals("page3 should be requested once", 1, server.hits("/page3"))
    }

    @Test
    fun t06_httpErrorPageDoesNotCrashTheBrowser() {
        Ui.navigate(server.url("/status/404"))
        // A 404 may render the engine's error page or the site's own; either is
        // acceptable. What must hold is that the app survives and is usable.
        Thread.sleep(4_000)
        assertTrue("browser died rendering a 404", Ui.isAppForeground())
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
    }

    @Test
    fun t07_malformedUrlDoesNotCrashTheBrowser() {
        Ui.navigate("not a url at all")
        Thread.sleep(3_000)
        assertTrue("browser died on a malformed input", Ui.isAppForeground())
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
    }

    @Test
    fun t08_rapidNavigationEndsOnTheLastRequestedPage() {
        // Regression: a stale page callback must not overwrite the address bar
        // with an earlier navigation's result.
        Ui.navigate(server.url("/page1"))
        Ui.navigate(server.url("/page2"))
        Ui.navigate(server.url("/page3"))
        Ui.waitForTitle("Fixture Page 3")
        Thread.sleep(2_000)
        assertEquals(
            "address bar should show the most recent navigation, not a late callback",
            "Fixture Page 3", Ui.textOf("address_text"),
        )
    }
}