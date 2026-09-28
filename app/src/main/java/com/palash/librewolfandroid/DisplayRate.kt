package com.palash.librewolfandroid

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import androidx.core.content.ContextCompat

/**
 * Refresh-rate handling.
 *
 * A browser is the app most likely to be judged on smoothness, and Android
 * gives a new window the display's *current* mode, which on many phones is
 * 60 Hz even when the panel can do 90 or 120. GeckoView composites on vsync, so
 * the engine can only present the frames the window is allowed to produce:
 * asking for the fastest mode at the current resolution is what separates a
 * 60 Hz scroll from a 120 Hz one.
 */
object DisplayRate {

    /**
     * The display behind this window.
     *
     * `Activity.getDisplay()` needs API 30 and the app supports 26, so the
     * androidx helper is the portable spelling of the same call.
     */
    private fun displayOf(context: Context): Display = ContextCompat.getDisplayOrDefault(context)

    /** Every mode the display offers, fastest first. */
    fun supportedModes(activity: Activity): List<Display.Mode> {
        val modes = displayOf(activity).supportedModes ?: return emptyList()
        return modes.sortedByDescending { it.refreshRate }
    }

    /**
     * Requests the fastest mode that keeps the window's current resolution.
     *
     * Resolution is not negotiable: a higher refresh rate at a different size
     * would letterbox the page and change what the user sees, which is not a
     * trade a browser should make on its own.
     *
     * Two requests are made, because one is not enough in practice. The mode id
     * is what the window asks for, and on a panel with adaptive refresh the
     * system treats that as a hint it may decline: it keeps the display at 60 Hz
     * for an app that is not asking to *produce* frames faster. The frame-rate
     * hint on the window's view is the request that says "I can present 120
     * frames a second", and it is what actually moves the display.
     */
    fun prefer(activity: Activity): Display.Mode? {
        val display = displayOf(activity)
        val current = currentMode(display) ?: return null
        val sameSize = display.supportedModes.orEmpty()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val best = sameSize.maxByOrNull { it.refreshRate } ?: return null
        if (best.modeId != current.modeId || best.refreshRate > current.refreshRate) {
            val attributes = activity.window.attributes
            attributes.preferredDisplayModeId = best.modeId
            attributes.preferredRefreshRate = best.refreshRate
            activity.window.attributes = attributes
        }
        applyFrameRateHint(activity, best.refreshRate)
        return best
    }

    /**
     * Asks the window's own view to present frames at this rate.
     *
     * The window attributes above are a mode request, and on a panel with
     * adaptive refresh the system may keep the display at 60 Hz for an app that
     * is not asking to *produce* frames faster. This is the request that says
     * "I can present 120 frames a second". It arrived as a public API in 35.
     */
    private fun applyFrameRateHint(activity: Activity, rate: Float) {
        if (rate <= 0f) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val view = activity.window.decorView ?: return
        runCatching { view.requestedFrameRate = rate }
    }

    /** The mode this window asked for, or null when it asked for none. */
    fun requestedModeId(activity: Activity): Int = activity.window.attributes.preferredDisplayModeId

    /**
     * The system-wide refresh cap, in Hz.
     *
     * This is the "Smooth display" toggle under Display settings, and it is a
     * hard ceiling: with it at 60 Hz no application can be given 120 Hz, no
     * matter what it asks for. Reading it is the difference between "the browser
     * asked and was refused" and "the phone was told not to".
     */
    fun systemPeakRate(context: Context): Float? = runCatching {
        // The constant is not in the public SDK, only the key itself is stable;
        // it is the "Smooth display" toggle in Display settings.
        val value = android.provider.Settings.System.getFloat(
            context.contentResolver,
            "peak_refresh_rate",
            0f,
        )
        value.takeIf { it > 0f }
    }.getOrNull()

    /** The refresh rate the window is actually running at, in Hz. */
    fun activeRefreshRate(activity: Activity): Float {
        val display = displayOf(activity)
        return currentMode(display)?.refreshRate ?: display.refreshRate
    }

    /**
     * Drops back to the display's own default.
     *
     * `preferredDisplayModeId` is sticky for the life of the window, so a window
     * that asked for 120 Hz keeps asking after a rotation onto a display that
     * cannot do it, where the system is then asked for a mode that does not
     * exist. Clearing it lets the platform choose.
     */
    fun release(activity: Activity) {
        val attributes = activity.window.attributes
        if (attributes.preferredDisplayModeId == 0) return
        attributes.preferredDisplayModeId = 0
        activity.window.attributes = attributes
    }

    @Suppress("DEPRECATION")
    private fun currentMode(display: Display): Display.Mode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display.mode
        } else {
            val size = android.util.DisplayMetrics()
            display.getRealMetrics(size)
            display.supportedModes?.firstOrNull {
                it.physicalWidth == size.widthPixels && it.physicalHeight == size.heightPixels
            }
        }

    /** "120 Hz" for the diagnostics screen. */
    fun describe(activity: Activity): String {
        val rate = activeRefreshRate(activity)
        if (rate <= 0f) return "unknown"
        // Panels report 120 as 120.00001, so compare on the rounded value
        // instead of the float remainder, and keep a real fraction when there
        // is one (59.94 is not 60).
        val rounded = Math.round(rate)
        val text = if (Math.abs(rate - rounded) < 0.5f) {
            rounded.toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", rate)
        }
        return "$text Hz"
    }

    /** The refresh rates on offer, fastest first, for the diagnostics screen. */
    fun describeModes(activity: Activity): String {
        val modes = supportedModes(activity)
        if (modes.isEmpty()) return "unknown"
        return modes.map { String.format(java.util.Locale.US, "%.0f", it.refreshRate) }
            .distinct()
            .joinToString(" / ") + " Hz"
    }
}
