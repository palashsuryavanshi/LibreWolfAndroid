package com.palash.librewolfandroid

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Edge-to-edge window handling.
 *
 * From Android 15 the platform draws every app edge to edge and the
 * `statusBarColor`/`navigationBarColor` theme attributes are ignored, so the shell
 * has to place its own content inside the system bars. System-bar and IME insets
 * are applied as padding on the content root, which also keeps the address bar
 * above the keyboard when it opens.
 */
object SystemBars {

    fun apply(activity: Activity, root: View = activity.findViewById(android.R.id.content)) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        // Ask for the fastest refresh rate the panel offers at this resolution.
        // A window otherwise starts on the display's current mode, which on many
        // phones is 60 Hz, and no amount of engine work makes a scroll smoother
        // than the frames the window is allowed to produce.
        DisplayRate.prefer(activity)
        applyIconAppearance(activity, root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Re-applies insets after a rotation, fold or other configuration change. */
    fun refresh(activity: Activity, root: View = activity.findViewById(android.R.id.content)) {
        applyIconAppearance(activity, root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyIconAppearance(activity: Activity, root: View) {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }
}
