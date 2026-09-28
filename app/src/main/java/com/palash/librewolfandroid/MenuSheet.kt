package com.palash.librewolfandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Bottom menu sheet mirroring the reference design (LibreWolf branding/actions). */
class MenuSheet : BottomSheetDialogFragment() {

    var onHistory: () -> Unit = {}
    var onBookmarks: () -> Unit = {}
    var onDownloads: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onQuit: () -> Unit = {}
    var onCompatibility: () -> Unit = {}
    var onPrivacyReport: () -> Unit = {}
    var canInstallPwa: Boolean = false
    var onInstallPwa: () -> Unit = {}

    /** False on the start page, where there is no site to troubleshoot. */
    var isHomepage: Boolean = true

    /**
     * Actions on the page in front, drawn as a tile row at the foot of the sheet.
     *
     * Each carries an icon for the same reason the navigation tiles do: a tile
     * with text alone is a different shape from its neighbours, and a row of
     * differently-shaped controls reads as a different kind of thing.
     */
    var pageActions: List<PageAction> = emptyList()

    /** One page action: a label, an icon and what to do. */
    class PageAction(val label: String, val icon: Int, val onClick: () -> Unit)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_menu, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.row_extensions).setOnClickListener {
            dismiss()
            context?.startActivity(Intent(context, ExtensionsActivity::class.java))
        }
        view.findViewById<View>(R.id.tile_history).setOnClickListener { dismiss(); onHistory() }
        view.findViewById<View>(R.id.tile_bookmarks).setOnClickListener { dismiss(); onBookmarks() }
        view.findViewById<View>(R.id.tile_downloads).setOnClickListener { dismiss(); onDownloads() }
        view.findViewById<View>(R.id.tile_passwords).setOnClickListener {
            dismiss()
            context?.startActivity(Intent(context, PasswordsActivity::class.java))
        }
        // No "Homepage" row: the pinned page-action tile at the foot of the sheet
        // goes to the start page, and a second control for the same destination
        // in the same sheet is the duplication that prompted removing it.
        view.findViewById<View>(R.id.row_install).apply {
            visibility = if (canInstallPwa) View.VISIBLE else View.GONE
            setOnClickListener { dismiss(); onInstallPwa() }
        }
        view.findViewById<View>(R.id.row_whatsnew).setOnClickListener {
            dismiss()
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/palashsuryavanshi/LibreWolfAndroid/releases"),
                ),
            )
        }
        view.findViewById<View>(R.id.row_settings).setOnClickListener { dismiss(); onSettings() }
        view.findViewById<View>(R.id.row_quit).setOnClickListener { dismiss(); onQuit() }

        // "Troubleshoot this site" is only useful with a page loaded: with the
        // start page there is no site to troubleshoot, so the row is hidden
        // rather than opening a page about nothing.
        view.findViewById<View>(R.id.row_compatibility).apply {
            val hasSite = MainActivity.runtimeRef() != null && !isHomepage
            visibility = if (hasSite) View.VISIBLE else View.GONE
            setOnClickListener { dismiss(); onCompatibility() }
        }

        // Privacy report row (only when enabled in Settings > Tabs)
        val ctx = context ?: return
        val store = PrivacyStore(ctx)
        view.findViewById<View>(R.id.row_privacy_report).visibility =
            if (store.privacyReport) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.row_privacy_report).setOnClickListener {
            dismiss()
            onPrivacyReport()
        }

        renderPageActions(view)
    }

    /**
     * Builds the page-action tiles into the grid above the navigation rows.
     *
     * Built in code rather than declared in the layout because the set is
     * conditional: forward only when there is somewhere to go, find-in-page only
     * with a page loaded, and so on. Fixed tiles would have to be greyed out for
     * actions that cannot apply, which is worse than not showing them.
     *
     * The tile is assembled to match the navigation tiles exactly -- same
     * background, 12dp padding, 24dp icon, 6dp gap, 13sp label -- so the two rows
     * read as one menu rather than a menu with a strip of oddities stuck on it.
     */
    private fun renderPageActions(view: View) {
        val grid = view.findViewById<GridLayout>(R.id.row_page_actions)
        // No hiding. The row is a fixed footer of four tiles; an empty or absent
        // footer would leave the sheet ending on whatever row happened to be
        // last, which moved around with the page.
        val ctx = context ?: return
        grid.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val iconSize = (24 * density).toInt()
        val labelGap = (6 * density).toInt()

        pageActions.forEach { action ->
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_card)
                isClickable = true
                isFocusable = true
                setPadding(pad, pad, pad, pad)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(
                        GridLayout.UNDEFINED,
                        1f,
                    )
                }
                setOnClickListener {
                    // Dismiss first, then act: an action that opens a page or
                    // clears data must not have the sheet still attached behind it.
                    dismiss()
                    action.onClick()
                }
            }
            tile.addView(
                android.widget.ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    setImageResource(action.icon)
                },
            )
            tile.addView(
                TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = labelGap }
                    text = action.label
                    gravity = android.view.Gravity.CENTER
                    maxLines = 2
                    setTextColor(ctx.getColor(R.color.librewolf_text))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                },
            )
            grid.addView(tile)
        }
    }
}
