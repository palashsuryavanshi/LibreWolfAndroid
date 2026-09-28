package com.palash.librewolfandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    var onHome: () -> Unit = {}
    var canInstallPwa: Boolean = false
    var onInstallPwa: () -> Unit = {}

    /** False on the start page, where there is no site to troubleshoot. */
    var isHomepage: Boolean = true

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
        view.findViewById<View>(R.id.row_homepage).setOnClickListener { dismiss(); onHome() }
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
    }
}
