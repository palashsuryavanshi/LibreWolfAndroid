package com.palash.librewolfandroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does tapping the address bar select the whole URL?
 *
 * This is the behaviour the user asked for: the field shows the page title
 * while idle, and on focus it must show the URL with the whole of it selected,
 * so one keystroke replaces it.
 *
 * The test drives a real focus change (defocus, then tap) because a
 * programmatic click on an already-focused view does not re-fire the focus
 * listener, which is what made the first attempt report an append when the
 * selection was in fact working.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AddressBarSelectionTest {

    private lateinit var server: FixtureServer
    private val device: UiDevice
        get() = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        server = FixtureServer()
        assertTrue("app did not start", Ui.launchBrowser())
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun focusingTheAddressBarSelectsTheWholeUrl() {
        Ui.navigate(server.url("/page1"))
        Ui.waitForTitle("Fixture Page 1")
        val url = Ui.textOf("address_text")
        assertTrue("a URL should be shown before focus", !url.isNullOrBlank())

        // Leave the field so the next tap is a genuine focus change.
        Ui.tap(540, 500)
        Thread.sleep(600)
        Ui.require("address_text").click()
        Thread.sleep(600)

        // One keystroke. If the whole URL was selected this replaces it; if
        // not, it is inserted at the cursor.
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_X)
        Thread.sleep(600)

        val after = Ui.textOf("address_text")
        assertTrue(
            "expected the keystroke to replace the selected URL, got '$after' (was '$url')",
            after != null && after.length == 1,
        )
    }
}