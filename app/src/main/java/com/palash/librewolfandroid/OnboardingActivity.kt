package com.palash.librewolfandroid

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The first-run walkthrough.
 *
 * Shown once, before the browser is usable, and it asks for the one Android
 * permission the browser itself needs. Camera, microphone and location are
 * deliberately *not* here: those belong to a site, at the moment a site asks,
 * which is the one rule this project has never bent. Downloading needs no
 * permission at all, because it goes through MediaStore.
 *
 * The completion flag lives in [PrivacyStore] rather than in this activity, so a
 * process death halfway through does not replay the whole thing on next launch.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var store: PrivacyStore

    private lateinit var step: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var note: TextView
    private lateinit var bullets: LinearLayout
    private lateinit var art: ImageView
    private lateinit var dots: LinearLayout
    private lateinit var action: TextView
    private lateinit var skip: TextView
    private lateinit var defaultRow: View
    private lateinit var defaultSwitch: CompoundButton

    private var page = 0

    /** False where the system has no browser role to hand out, e.g. Android 8. */
    private var roleAvailable = false

    private val pages: List<Page> by lazy { buildPages() }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The one-and-only ask, done at a moment the user is looking at.
            store.notificationPermissionAsked = true
            Toast.makeText(
                this,
                if (granted) R.string.onboarding_notifications_granted
                else R.string.onboarding_notifications_denied,
                Toast.LENGTH_LONG,
            ).show()
            go(page + 1)
        }

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val held = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                roleManager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
            } else {
                false
            }
            defaultSwitch.isChecked = held
            Toast.makeText(
                this,
                if (result.resultCode == Activity.RESULT_OK && held) R.string.onboarding_default_browser_done
                else R.string.onboarding_default_browser_declined,
                Toast.LENGTH_LONG,
            ).show()
        }

    private val roleManager: RoleManager?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)
        } else {
            null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        SystemBars.apply(this)
        store = PrivacyStore(this)

        step = findViewById(R.id.onboarding_step)
        title = findViewById(R.id.onboarding_title)
        this.body = findViewById(R.id.onboarding_body)
        note = findViewById(R.id.onboarding_note)
        bullets = findViewById(R.id.onboarding_bullets)
        art = findViewById(R.id.onboarding_art)
        dots = findViewById(R.id.onboarding_dots)
        action = findViewById(R.id.onboarding_action)
        skip = findViewById(R.id.onboarding_skip)
        defaultRow = findViewById(R.id.onboarding_default_row)
        defaultSwitch = findViewById(R.id.onboarding_default_switch)

        skip.setOnClickListener { finishOnboarding() }
        action.setOnClickListener { onAction() }

        // A back press walks the pages, and only the last one leaves. Without this
        // a stray back would drop the user into an unexplained browser.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (page == 0) finishOnboarding() else go(page - 1)
                }
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = roleManager
            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                roleAvailable = true
                defaultSwitch.isChecked = manager.isRoleHeld(RoleManager.ROLE_BROWSER)
                defaultSwitch.setOnClickListener {
                    // The system shows its own confirmation, so hand the request
                    // over rather than pretending to grant anything here.
                    defaultSwitch.isChecked = !defaultSwitch.isChecked
                    runCatching { manager.createRequestRoleIntent(RoleManager.ROLE_BROWSER) }
                        .onSuccess { roleLauncher.launch(it) }
                        .onFailure { defaultSwitch.isChecked = !defaultSwitch.isChecked }
                }
            }
        }

        render()
    }

    private fun onAction() {
        val current = pages[page]
        if (current.action == Action.GRANT_NOTIFICATIONS) {
            requestNotifications()
            return
        }
        if (page == pages.lastIndex) {
            finishOnboarding()
            return
        }
        go(page + 1)
    }

    /**
     * The single notification ask.
     *
     * If Android has already permanently denied it, launching would produce
     * nothing at all, so the settings page is opened instead and the user is told
     * where they are rather than watching a button appear to do nothing.
     */
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            go(page + 1)
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.onboarding_notifications_already, Toast.LENGTH_SHORT).show()
            go(page + 1)
            return
        }
        if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
            store.notificationPermissionAsked
        ) {
            Toast.makeText(this, R.string.onboarding_notifications_denied, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null),
                ),
            )
            return
        }
        store.notificationPermissionAsked = true
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun go(index: Int) {
        page = index.coerceIn(0, pages.lastIndex)
        render()
    }

    private fun finishOnboarding() {
        store.onboardingComplete = true
        finish()
    }

    private fun render() {
        val current = pages[page]
        step.text = getString(R.string.onboarding_page_x_of_y, page + 1, pages.size)
        title.text = current.title
        body.text = current.body
        note.text = current.note
        note.visibility = if (current.note.isBlank()) View.GONE else View.VISIBLE
        art.visibility = if (current.art == 0) View.GONE else View.VISIBLE
        current.art.takeIf { it != 0 }?.let { art.setImageResource(it) }

        bullets.removeAllViews()
        current.bullets.forEach { line ->
            val row = TextView(this)
            row.text = line
            row.setTextColor(ContextCompat.getColor(this, R.color.librewolf_text))
            row.textSize = 15f
            row.setLineSpacing(0f, 1.2f)
            row.setPadding(0, 0, 0, 0)
            val holder = LinearLayout(this)
            holder.orientation = LinearLayout.HORIZONTAL
            holder.setPadding(0, (9 * density()).toInt(), 0, 0)
            val dot = View(this)
            val dotParams = LinearLayout.LayoutParams((5 * density()).toInt(), (5 * density()).toInt())
            dotParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            dotParams.topMargin = (7 * density()).toInt()
            dotParams.marginEnd = (14 * density()).toInt()
            dot.setBackgroundResource(R.drawable.onboarding_dot)
            dot.isSelected = true
            holder.addView(dot, dotParams)
            holder.addView(
                row,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            bullets.addView(holder)
        }
        bullets.visibility = if (current.bullets.isEmpty()) View.GONE else View.VISIBLE

        action.text = getString(current.actionLabel)
        // The default-browser switch belongs only on the last page, and only where
        // the system actually offers the role.
        defaultRow.visibility =
            if (page == pages.lastIndex && roleAvailable) View.VISIBLE else View.GONE
        // On the last page the action finishes, so "Skip" would be a second,
        // differently-worded way of doing the same thing.
        skip.visibility = if (page == pages.lastIndex) View.INVISIBLE else View.VISIBLE

        renderDots()
    }

    private fun renderDots() {
        dots.removeAllViews()
        repeat(pages.size) { index ->
            val dot = View(this)
            val size = (7 * density()).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            params.marginStart = (4 * density()).toInt()
            params.marginEnd = (4 * density()).toInt()
            dot.setBackgroundResource(R.drawable.onboarding_dot)
            dot.isSelected = index == page
            dot.contentDescription = getString(R.string.onboarding_page_x_of_y, index + 1, pages.size)
            dots.addView(dot, params)
        }
    }

    private fun density(): Float = resources.displayMetrics.density

    private fun buildPages(): List<Page> = listOf(
        Page(
            title = getString(R.string.onboarding_welcome_title),
            body = getString(R.string.onboarding_welcome_body),
            note = getString(R.string.onboarding_unofficial),
            art = R.drawable.ic_globe,
            action = Action.NEXT,
            actionLabel = R.string.onboarding_next,
        ),
        Page(
            title = getString(R.string.onboarding_protection_title),
            body = getString(R.string.onboarding_protection_body),
            bullets = listOf(
                getString(R.string.onboarding_bullet_tracking),
                getString(R.string.onboarding_bullet_cookies),
                getString(R.string.onboarding_bullet_https),
                getString(R.string.onboarding_bullet_private),
                getString(R.string.onboarding_bullet_telemetry),
            ),
            art = R.drawable.ic_shield,
            action = Action.NEXT,
            actionLabel = R.string.onboarding_next,
        ),
        Page(
            title = getString(R.string.onboarding_notifications_title),
            body = getString(R.string.onboarding_notifications_body),
            art = R.drawable.ic_dl,
            action = Action.GRANT_NOTIFICATIONS,
            actionLabel = R.string.onboarding_notifications_allow,
        ),
        Page(
            title = getString(R.string.onboarding_ready_title),
            body = getString(R.string.onboarding_ready_body),
            art = R.drawable.ic_globe,
            action = Action.FINISH,
            actionLabel = R.string.onboarding_start,
        ),
    )

    private enum class Action { NEXT, GRANT_NOTIFICATIONS, FINISH }

    private class Page(
        val title: String,
        val body: String,
        val note: String = "",
        val bullets: List<String> = emptyList(),
        val art: Int = 0,
        val action: Action,
        val actionLabel: Int,
    )

    companion object {
        /** True when the walkthrough still owes the user a run. */
        fun isNeeded(context: android.content.Context): Boolean =
            !PrivacyStore(context).onboardingComplete
    }
}
