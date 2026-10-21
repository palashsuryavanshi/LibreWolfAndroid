package com.palash.librewolfandroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests that external links (http/https URIs from other apps) open in a new
 * tab rather than replacing the current tab.
 *
 * These tests send a real Android intent to the app, simulating what happens
 * when another app (e.g., a messaging app) sends a web link to LibreWolf.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ExternalLinkTest {

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

    /** Send an external http(s) intent to the app, as another app would. */
    private fun sendExternalUri(url: String) {
        Ui.device.executeShellCommand(
            "am start -a android.intent.action.VIEW -d \"$url\" -p ${Ui.pkg}"
        )
        Thread.sleep(3_000)
    }

    @Test
    fun externalLinkOpensNewTabWhenOtherTabsExist() {
        // Precondition: open one normal tab
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        val beforeCount = Ui.tabCount()
        assertTrue("could not read tab count", beforeCount >= 0)

        // Send an external intent for a different page
        sendExternalUri(server.url("/page2"))

        // Both tabs should exist
        val afterCount = Ui.tabCount()
        assertEquals(
            "expected 2 tabs after external link",
            beforeCount + 1,
            afterCount
        )

        // The new tab should be active and show page2
        Ui.waitForTitle("Fixture Page 2")
    }

    @Test
    fun externalLinkCreatesFirstTabWhenNoneExist() {
        // No tabs open yet
        val beforeCount = Ui.tabCount()
        assertTrue("could not read tab count", beforeCount >= 0)

        // Send an external intent for a page
        sendExternalUri(server.url("/page1"))

        // A tab should have been created
        val afterCount = Ui.tabCount()
        assertEquals("one tab should exist after external link", 1, afterCount)

        // The new tab should show page1
        Ui.waitForTitle("Fixture Page 1")
    }

    @Test
    fun externalLinkDoesNotReplaceExistingTab() {
        // Open two tabs first
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        Ui.navigate(server.url("/page2"))
        Ui.waitForTitle("Fixture Page 2")

        val beforeCount = Ui.tabCount()
        assertTrue("could not read tab count", beforeCount >= 2)

        // Send an external intent for a third page
        sendExternalUri(server.url("/page3"))

        // All three tabs should exist
        val afterCount = Ui.tabCount()
        assertEquals(
            "expected 3 tabs after external link",
            beforeCount + 1,
            afterCount
        )

        // The new tab should be active
        Ui.waitForTitle("Fixture Page 3")

        // The first two tabs should still exist
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        Ui.navigate(server.url("/page2"))
        Ui.waitForTitle("Fixture Page 2")
    }
}