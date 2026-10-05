package com.palash.librewolfandroid

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.mozilla.geckoview.StorageController

/**
 * The settings component framework.
 *
 * A settings screen is data, not code: a list of sections, each holding
 * components. The renderer in [SettingsActivity] draws them, so navigation,
 * insets, theming and the search index are written once and every later page is
 * four or five declarations.
 *
 * A component is only added when something implements it. `info` and `action`
 * exist precisely so a screen can *show* a fact or offer a jump to a place
 * where the real work happens, instead of growing a switch that does nothing.
 */

/** What a component needs to draw and act: the activity and its stores. */
class SettingsContext(val activity: AppCompatActivity) {
    val store: PrivacyStore = PrivacyStore(activity)
    val history: HistoryStore = HistoryStore(activity)
    val bookmarks: BookmarkStore = BookmarkStore(activity)
    val logins: LoginStore = LoginStore(activity)
    val sitePermissions: SitePermissionStore = SitePermissionStore(activity)
    val downloads: DownloadStore = DownloadStore(activity)

    /**
     * Set by the renderer so a component can ask for a repaint after it changes
     * a value. Without it a choice row keeps showing the value it had before the
     * tap, which reads as "the tap did nothing".
     */
    var onNeedsRender: (() -> Unit)? = null

    fun repaint() {
        onNeedsRender?.invoke()
    }

    fun string(id: Int): String = activity.getString(id)

    fun open(screenId: String) {
        activity.startActivity(
            Intent(activity, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SCREEN, screenId),
        )
    }

    fun activity_(intent: Intent) {
        runCatching { activity.startActivity(intent) }
    }

    /**
     * Launches an activity for a result. The actual launcher lives on the
     * activity and is registered in its constructor, because
     * `registerForActivityResult` must run before the activity is STARTED --
     * registering lazily from a dialog button (RESUMED) throws.
     */
    fun activityForResult(
        intent: Intent,
        onResult: (android.content.Intent?) -> Unit,
    ) {
        (activity as? SettingsActivity)?.launchFolderPicker(intent, onResult)
            ?: runCatching { activity.startActivity(intent) }
    }

    fun refresh() {
        // Re-creating the activity from inside a click handler tears the view
        // tree down while that same tap is still being dispatched. The row and
        // the switch on it are separate click targets, so the second one then
        // runs against a detached view and writes the value back: the setting
        // lands on what it already was and the switch looks dead. Posting the
        // re-creation lets the tap finish first, so the value is written once.
        val target = activity
        target.window.decorView.post {
            if (!target.isFinishing && !target.isDestroyed) target.recreate()
        }
    }

    fun toast(message: String) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun confirm(
        title: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
        if (destructive) builder.setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
        builder.show()
    }
}

sealed class Setting {
    abstract val title: String
    open val subtitle: String? = null
    open val icon: Int? = null

    /** Extra words the search should match, beyond the title and subtitle. */
    open val keywords: List<String> = emptyList()

    /** When false the row is drawn dimmed and cannot be tapped. */
    open val enabled: Boolean = true
}

/** Opens another settings screen by id. */
class NavSetting(
    override val title: String,
    override val subtitle: String? = null,
    override val icon: Int? = null,
    override val keywords: List<String> = emptyList(),
    val screenId: String,
) : Setting()

/** Runs shell code: clears something, opens a system screen, shows a dialog. */
class ActionSetting(
    override val title: String,
    override val subtitle: String? = null,
    override val icon: Int? = null,
    override val keywords: List<String> = emptyList(),
    val onClick: (SettingsContext) -> Unit,
) : Setting()

/** A switch bound to a stored boolean. */
class ToggleSetting(
    override val title: String,
    override val subtitle: String? = null,
    override val icon: Int? = null,
    override val keywords: List<String> = emptyList(),
    val current: Boolean,
    val read: (SettingsContext) -> Boolean,
    val write: (SettingsContext, Boolean) -> Unit,
    val onChanged: (SettingsContext) -> Unit = {},
) : Setting()

/** One value out of a fixed list, chosen in a dialog. */
class ChoiceSetting(
    override val title: String,
    override val subtitle: String? = null,
    override val icon: Int? = null,
    override val keywords: List<String> = emptyList(),
    val options: List<String>,
    val current: String,
    val readIndex: (SettingsContext) -> Int,
    val writeIndex: (SettingsContext, Int) -> Unit,
    val onChanged: (SettingsContext) -> Unit = {},
    val dialogMessage: String? = null,
) : Setting()

/** An integer over a range, adjusted with a slider. */
class SliderSetting(
    override val title: String,
    override val subtitle: String? = null,
    override val icon: Int? = null,
    override val keywords: List<String> = emptyList(),
    val range: IntRange,
    val current: Int,
    val read: (SettingsContext) -> Int,
    val write: (SettingsContext, Int) -> Unit,
    val format: (Int) -> String = { it.toString() },
) : Setting()

/** Free text, edited in a dialog. */
class TextSetting(
    override val title: String,
    override val subtitle: String? = null,
    override val icon: Int? = null,
    override val keywords: List<String> = emptyList(),
    val message: String? = null,
    val current: String,
    val read: (SettingsContext) -> String,
    val write: (SettingsContext, String) -> Unit,
) : Setting()

/** A read-only fact. Not tappable: there is nothing behind it. */
class InfoSetting(
    override val title: String,
    val value: String,
    override val subtitle: String? = null,
    override val icon: Int? = null,
    override val keywords: List<String> = emptyList(),
) : Setting()

class Section(val title: String?, val subtitle: String?, val settings: List<Setting>, val footer: String? = null)

class Screen(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val keywords: List<String> = emptyList(),
    val build: SettingsContext.() -> List<Section>,
)

class SectionBuilder(private val ctx: SettingsContext) {
    internal val settings = mutableListOf<Setting>()

    fun nav(title: String, screenId: String, subtitle: String? = null, icon: Int? = null, vararg keywords: String) {
        settings += NavSetting(title, subtitle, icon, keywords.toList(), screenId)
    }

    fun action(title: String, onClick: (SettingsContext) -> Unit, subtitle: String? = null, icon: Int? = null, vararg keywords: String) {
        settings += ActionSetting(title, subtitle, icon, keywords.toList(), onClick)
    }

    fun toggle(
        title: String,
        read: (SettingsContext) -> Boolean,
        write: (SettingsContext, Boolean) -> Unit,
        subtitle: String? = null,
        icon: Int? = null,
        onChanged: (SettingsContext) -> Unit = {},
        vararg keywords: String,
    ) {
        // The current value is captured here, while the screen is being built
        // against a live context, so the search index can show it without
        // re-evaluating a lambda it has no context for.
        val value = read(ctx)
        settings += ToggleSetting(title, subtitle, icon, keywords.toList(), value, read, write, onChanged)
    }

    fun choice(
        title: String,
        options: List<String>,
        readIndex: (SettingsContext) -> Int,
        writeIndex: (SettingsContext, Int) -> Unit,
        subtitle: String? = null,
        icon: Int? = null,
        message: String? = null,
        onChanged: (SettingsContext) -> Unit = {},
        vararg keywords: String,
    ) {
        val index = readIndex(ctx)
        val value = options.getOrNull(index.coerceIn(0, options.lastIndex.coerceAtLeast(0))).orEmpty()
        settings += ChoiceSetting(title, subtitle, icon, keywords.toList(), options, value, readIndex, writeIndex, onChanged, message)
    }

    fun slider(
        title: String,
        range: IntRange,
        read: (SettingsContext) -> Int,
        write: (SettingsContext, Int) -> Unit,
        subtitle: String? = null,
        icon: Int? = null,
        format: (Int) -> String = { it.toString() },
        vararg keywords: String,
    ) {
        val value = read(ctx).coerceIn(range.first, range.last)
        settings += SliderSetting(title, subtitle, icon, keywords.toList(), range, value, read, write, format)
    }

    fun text(
        title: String,
        read: (SettingsContext) -> String,
        write: (SettingsContext, String) -> Unit,
        subtitle: String? = null,
        icon: Int? = null,
        message: String? = null,
        vararg keywords: String,
    ) {
        val value = read(ctx)
        settings += TextSetting(title, subtitle, icon, keywords.toList(), message, value, read, write)
    }

    fun info(title: String, value: String, subtitle: String? = null, icon: Int? = null, vararg keywords: String) {
        settings += InfoSetting(title, value, subtitle, icon, keywords.toList())
    }
}

class ScreenBuilder(private val ctx: SettingsContext) {
    internal val sections = mutableListOf<Section>()

    fun section(title: String? = null, subtitle: String? = null, footer: String? = null, build: SectionBuilder.() -> Unit) {
        val builder = SectionBuilder(ctx)
        builder.build()
        sections += Section(title, subtitle, builder.settings, footer)
    }
}

/** Builds a screen's sections against a context. */
fun screen(id: String, title: String, subtitle: String? = null, vararg keywords: String, build: SettingsContext.() -> List<Section>): Screen =
    Screen(id, title, subtitle, keywords.toList(), build)

fun SettingsContext.sections(build: ScreenBuilder.() -> Unit): List<Section> = ScreenBuilder(this).apply(build).sections

// ---- rendering ----

/**
 * Draws one component into the list.
 *
 * Kept as a top-level function rather than a method on the activity so the
 * renderer has no hidden state: everything it needs is the context, the list it
 * appends to, and the component.
 */
internal fun renderSetting(ctx: SettingsContext, list: LinearLayout, setting: Setting) {
    when (setting) {
        is NavSetting -> row(ctx, list, setting, subtitle = setting.subtitle) { ctx.open(setting.screenId) }
        is ActionSetting -> row(ctx, list, setting, subtitle = setting.subtitle) { setting.onClick(ctx) }
        is InfoSetting -> row(ctx, list, setting, subtitle = setting.value + (setting.subtitle?.let { " · $it" } ?: "")) {}
        is ToggleSetting -> toggleRow(ctx, list, setting)
        is ChoiceSetting -> choiceRow(ctx, list, setting)
        is SliderSetting -> sliderRow(ctx, list, setting)
        is TextSetting -> textRow(ctx, list, setting)
    }
}

private fun row(ctx: SettingsContext, list: LinearLayout, setting: Setting, subtitle: String?, onClick: () -> Unit) {
    val view = ctx.activity.layoutInflater.inflate(R.layout.item_setting, list, false)
    view.findViewById<TextView>(R.id.set_title).text = setting.title
    val sub = view.findViewById<TextView>(R.id.set_sub)
    if (subtitle.isNullOrEmpty()) sub.visibility = View.GONE else sub.text = subtitle
    val iconView = view.findViewById<android.widget.ImageView>(R.id.set_icon)
    val iconRes = setting.icon
    if (iconRes == null) iconView.visibility = View.GONE else iconView.setImageResource(iconRes)
    view.findViewById<View>(R.id.set_switch).visibility = View.GONE
    view.isEnabled = setting.enabled
    view.alpha = if (setting.enabled) 1f else 0.5f
    view.setOnClickListener { if (setting.enabled) onClick() }
    list.addView(view)
}

private fun toggleRow(ctx: SettingsContext, list: LinearLayout, setting: ToggleSetting) {
    val view = ctx.activity.layoutInflater.inflate(R.layout.item_setting, list, false)
    view.findViewById<TextView>(R.id.set_title).text = setting.title
    val sub = view.findViewById<TextView>(R.id.set_sub)
    if (setting.subtitle.isNullOrEmpty()) sub.visibility = View.GONE else sub.text = setting.subtitle
    view.findViewById<android.widget.ImageView>(R.id.set_icon).visibility = View.GONE
    val toggle = view.findViewById<SwitchCompat>(R.id.set_switch)
    toggle.visibility = View.VISIBLE
    toggle.isEnabled = setting.enabled
    // The switch only ever *shows* the stored value. It has no write-through
    // listener, and the row is the click target, because a switch that writes
    // on its own change cannot be told apart from a change the platform made.
    // Two of those arrive without the user touching anything: Android saves the
    // checked state of a CompoundButton and restores it when the activity is
    // re-created, which is how a page that re-creates itself ends up putting
    // the outgoing switch's value back over the one just chosen — the setting
    // then reads as dead, and only for the switches on a page that refreshes.
    toggle.isClickable = false
    toggle.isFocusable = false
    view.setOnClickListener {
        if (!setting.enabled) return@setOnClickListener
        // The store decides what "the other value" is, not the switch: if a
        // restore has just left the switch showing something stale, toggling
        // the switch would save the stale reading back.
        val next = !setting.read(ctx)
        setting.write(ctx, next)
        toggle.isChecked = setting.read(ctx)
        setting.onChanged(ctx)
        ctx.repaint()
    }
    toggle.isChecked = setting.read(ctx)
    list.addView(view)
}

/**
 * A single-choice dialog with room for an explanation.
 *
 * `AlertDialog.setMessage` and `setSingleChoiceItems` cannot coexist: whichever
 * is set second is dropped, so a message silently hides the options. The dialog
 * is therefore built by hand — explanation on top, one radio per option — which
 * also lets the explanation say why an option is a bad idea, which is the whole
 * point of a privacy setting.
 */
private fun choiceRow(ctx: SettingsContext, list: LinearLayout, setting: ChoiceSetting) {
    val current = setting.readIndex(ctx).coerceIn(0, setting.options.lastIndex)
    row(ctx, list, setting, setting.options[current]) {
        val container = LinearLayout(ctx.activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        if (!setting.dialogMessage.isNullOrEmpty()) {
            container.addView(TextView(ctx.activity).apply {
                text = setting.dialogMessage
                textSize = 15f
                setTextColor(ctx.activity.getColor(R.color.librewolf_grey))
                setPadding(0, 0, 0, 16)
            })
        }
        val group = android.widget.RadioGroup(ctx.activity)
        val buttons = setting.options.mapIndexed { index, label ->
            android.widget.RadioButton(ctx.activity).apply {
                text = label
                textSize = 16f
                setTextColor(ctx.activity.getColor(R.color.librewolf_text))
                tag = index
            }.also { group.addView(it) }
        }
        // The group is told which one is selected *after* the buttons are added.
        // Marking a button before it joins the group leaves two of them checked,
        // because the exclusivity logic only runs as children arrive.
        group.check(buttons[current].id)
        container.addView(group)
        val scroll = android.widget.ScrollView(ctx.activity)
        scroll.addView(container)
        val dialog = AlertDialog.Builder(ctx.activity)
            .setTitle(setting.title)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .create()
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = buttons.indexOfFirst { it.id == checkedId }
            if (index < 0) return@setOnCheckedChangeListener
            setting.writeIndex(ctx, index)
            dialog.dismiss()
            setting.onChanged(ctx)
            ctx.repaint()
        }
        dialog.show()
    }
}

private fun sliderRow(ctx: SettingsContext, list: LinearLayout, setting: SliderSetting) {
    val view = ctx.activity.layoutInflater.inflate(R.layout.item_setting_slider, list, false)
    view.findViewById<TextView>(R.id.set_title).text = setting.title
    val value = view.findViewById<TextView>(R.id.set_value)
    val bar = view.findViewById<SeekBar>(R.id.set_slider)
    val span = setting.range.last - setting.range.first
    bar.max = span.coerceAtLeast(1)
    bar.progress = (setting.read(ctx) - setting.range.first).coerceIn(0, span)
    value.text = setting.format(setting.read(ctx))
    bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            value.text = setting.format(setting.range.first + progress)
        }

        override fun onStartTrackingTouch(bar: SeekBar?) = Unit

        override fun onStopTrackingTouch(bar: SeekBar?) {
            setting.write(ctx, setting.range.first + (bar?.progress ?: 0))
            ctx.repaint()
        }
    })
    list.addView(view)
}

private fun textRow(ctx: SettingsContext, list: LinearLayout, setting: TextSetting) {
    row(ctx, list, setting, setting.read(ctx)) {
        val input = EditText(ctx.activity).apply { setText(setting.read(ctx)) }
        val builder = AlertDialog.Builder(ctx.activity)
            .setTitle(setting.title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                setting.write(ctx, input.text.toString().trim())
                ctx.repaint()
            }
        if (setting.message != null) builder.setMessage(setting.message)
        builder.show()
    }
}

// ---- search ----

/** One searchable setting, with the screen it lives on. */
class SettingsSearchHit(
    val screenId: String,
    val screenTitle: String,
    val setting: Setting,
)

/**
 * Walks every screen and indexes its components.
 *
 * The screens are ordinary lambdas over a live context, so the index is built by
 * asking each one what it contains. That keeps the index and the screens from
 * ever disagreeing: there is no second list to maintain.
 */
object SettingsIndex {
    fun build(ctx: SettingsContext): List<SettingsSearchHit> {
        val hits = mutableListOf<SettingsSearchHit>()
        for (screen in SettingsScreens.all()) {
            for (section in screen.build(ctx)) {
                for (setting in section.settings) {
                    if (setting is NavSetting && setting.screenId == screen.id) continue
                    hits += SettingsSearchHit(screen.id, screen.title, setting)
                }
            }
        }
        return hits
    }

    fun search(hits: List<SettingsSearchHit>, query: String): List<SettingsSearchHit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return hits.filter { hit ->
            hit.setting.title.lowercase().contains(needle) ||
                hit.setting.subtitle?.lowercase()?.contains(needle) == true ||
                hit.setting.keywords.any { it.lowercase().contains(needle) } ||
                hit.screenTitle.lowercase().contains(needle)
        }
    }
}

/** Shared clearing logic, so every screen clears the same things the same way. */
object SettingsClear {
    const val COOKIES = 0
    const val CACHE = 1
    const val SITE_DATA = 2
    const val HISTORY = 3
    const val BOOKMARKS = 4
    const val PASSWORDS = 5
    const val DOWNLOADS = 6

    fun labels(ctx: SettingsContext): Array<String> = arrayOf(
        ctx.string(R.string.clear_cookies),
        ctx.string(R.string.clear_cache),
        ctx.string(R.string.clear_site_data),
        ctx.string(R.string.clear_history),
        ctx.string(R.string.clear_bookmarks),
        ctx.string(R.string.clear_passwords),
        ctx.string(R.string.clear_downloads),
    )

    fun apply(ctx: SettingsContext, selected: BooleanArray) {
        var engineFlags = 0L
        if (selected[COOKIES]) engineFlags = engineFlags or StorageController.ClearFlags.COOKIES
        if (selected[CACHE]) engineFlags = engineFlags or StorageController.ClearFlags.ALL_CACHES
        if (selected[SITE_DATA]) engineFlags = engineFlags or StorageController.ClearFlags.SITE_DATA
        if (engineFlags != 0L) MainActivity.eraseEngineData(engineFlags)
        if (selected[HISTORY]) ctx.history.clear()
        if (selected[BOOKMARKS]) ctx.bookmarks.clear()
        if (selected[PASSWORDS]) ctx.logins.clear()
        if (selected[DOWNLOADS]) clearDownloads(ctx)
        if (selected[SITE_DATA]) ctx.sitePermissions.clear()
        if (selected[SITE_DATA] || selected[HISTORY]) BrowserSessionStore(ctx.activity).clear()
        ctx.toast(ctx.string(R.string.data_cleared))
        ctx.activity_(Intent(ctx.activity, MainActivity::class.java).setAction(MainActivity.ACTION_DATA_CLEARED))
        ctx.activity.finish()
    }

    private fun clearDownloads(ctx: SettingsContext) {
        ctx.downloads.all().forEach { DownloadEngine.remove(it.id) }
        ctx.downloads.clearAll()
        runCatching {
            val manager = ctx.activity.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            manager.query(android.app.DownloadManager.Query()).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_ID)
                val ids = mutableListOf<Long>()
                while (cursor.moveToNext()) ids += cursor.getLong(idIndex)
                ids.forEach(manager::remove)
            }
        }
    }
}
