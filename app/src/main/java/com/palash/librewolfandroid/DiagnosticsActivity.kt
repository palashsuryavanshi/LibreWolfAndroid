package com.palash.librewolfandroid

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.StatFs
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Diagnostics.
 *
 * Everything here is read from the running process: the engine build, the ABI the
 * engine was actually loaded for, the refresh rate the window really got, the
 * process count, the heap in use, and how much of the data directory the
 * profile occupies. Nothing is collected, stored or sent anywhere — a browser
 * that claims to be private should be able to show its own state on demand, and
 * a bug report can be answered with a screenshot of this screen.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.diagnostics)
            textSize = 24f
            setTextColor(getColor(R.color.librewolf_text))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 24)
        }
        scroll.addView(list)
        root.addView(scroll)
        setContentView(root)
        SystemBars.apply(this)
        render(list)
    }

    private fun render(list: LinearLayout) {
        list.removeAllViews()

        section(list, getString(R.string.diag_engine))
        row(list, getString(R.string.diag_engine_version), BuildConfig.UPSTREAM_FIREFOX)
        row(list, getString(R.string.diag_librewolf_release), BuildConfig.LIBREWOLF_RELEASE)
        row(list, getString(R.string.diag_app_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        row(list, getString(R.string.diag_abi), Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")

        section(list, getString(R.string.diag_device))
        row(list, getString(R.string.diag_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        row(list, getString(R.string.diag_model), "${Build.MANUFACTURER} ${Build.MODEL}")

        section(list, getString(R.string.diag_display))
        row(list, getString(R.string.diag_refresh_rate), DisplayRate.describe(this))
        row(list, getString(R.string.diag_refresh_requested), requestedRateLabel())
        row(list, getString(R.string.diag_refresh_modes), DisplayRate.describeModes(this))

        section(list, getString(R.string.diag_process))
        val runtime = MainActivity.runtimeRef()
        row(list, getString(R.string.diag_engine_running), if (runtime == null) "no" else "yes")
        row(list, getString(R.string.diag_content_processes), countProcesses().toString())
        val system = ActivityManager.MemoryInfo()
        runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(system)
        }
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        row(
            list,
            getString(R.string.diag_app_memory),
            "${Format.bytes(this, memory.totalPss.toLong() * 1024L)} pss, " +
                "${Format.bytes(this, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())} java",
        )
        if (system.lowMemory) row(list, getString(R.string.diag_system_memory), getString(R.string.diag_low_memory))

        section(list, getString(R.string.diag_storage))
        row(list, getString(R.string.diag_profile_size), Format.bytes(this, profileSize()))

        if (BuildConfig.DEBUG) {
            section(list, getString(R.string.diag_developer))
            list.addView(Switch(this).apply {
                text = getString(R.string.settings_remote)
                isChecked = PrivacyStore(this@DiagnosticsActivity).remoteDebugging
                setOnCheckedChangeListener { _, checked ->
                    PrivacyStore(this@DiagnosticsActivity).remoteDebugging = checked
                    // The socket belongs to the runtime, so the change takes
                    // effect on the next launch rather than immediately.
                    toast(getString(R.string.diag_take_effect))
                }
            })
        }
    }

    /**
     * What the window asked for, next to what it is getting.
     *
     * A display with adaptive refresh may grant a frame-rate request, defer it,
     * or refuse it, and the difference is the difference between a 60 Hz and a
     * 120 Hz scroll. Reporting only the request would be a claim; reporting only
     * the result would hide a request that never landed.
     */
    private fun requestedRateLabel(): String {
        val modeId = DisplayRate.requestedModeId(this)
        val requested = DisplayRate.supportedModes(this).firstOrNull { it.modeId == modeId }
            ?: DisplayRate.supportedModes(this).firstOrNull()
            ?: return "unknown"
        val actual = DisplayRate.activeRefreshRate(this)
        if (requested.refreshRate <= actual + 0.5f) {
            return String.format(java.util.Locale.US, "%.0f Hz", requested.refreshRate)
        }
        val peak = DisplayRate.systemPeakRate(this)
        return if (peak != null && peak < requested.refreshRate) {
            String.format(
                java.util.Locale.US,
                "%.0f Hz (system peak is %.0f Hz)",
                requested.refreshRate,
                peak,
            )
        } else {
            String.format(java.util.Locale.US, "%.0f Hz (declined)", requested.refreshRate)
        }
    }

    /**
     * Gecko forks a content process per tab plus a GPU and a crash handler, so
     * this count is the honest answer to "how much is this page costing me".
     * Processes of other apps are not visible and are not counted.
     */
    private fun countProcesses(): Int {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val mine = android.os.Process.myPid()
        return runCatching {
            activityManager.runningAppProcesses.count { it.pid != mine }
        }.getOrDefault(0)
    }

    private fun profileSize(): Long = runCatching {
        java.io.File(applicationInfo.dataDir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun section(list: LinearLayout, title: String) {
        list.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(getColor(R.color.librewolf_accent))
            setPadding(0, 20, 0, 6)
        })
    }

    private fun row(list: LinearLayout, label: String, value: String) {
        list.addView(
            TextView(this).apply {
                text = "$label: $value"
                textSize = 15f
                setTextColor(getColor(R.color.librewolf_text))
                setPadding(0, 4, 0, 4)
            },
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
