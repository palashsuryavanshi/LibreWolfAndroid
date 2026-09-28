package com.palash.librewolfandroid

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

/**
 * The settings hierarchy.
 *
 * Every screen is data. A screen that would need an engine API GeckoView 147
 * does not have is simply not here, and the reason is recorded in
 * `docs/SETTINGS-PLAN.md` and `TESTING.md` rather than faked with a switch that
 * does nothing.
 */
object SettingsScreens {

    const val ROOT = "root"
    const val PRIVACY = "privacy"
    const val PRIVACY_TRACKING = "privacy_tracking"
    const val PRIVACY_FINGERPRINTING = "privacy_fingerprinting"
    const val PRIVACY_COOKIES = "privacy_cookies"
    const val PRIVACY_HTTPS = "privacy_https"
    const val PRIVACY_DNS = "privacy_dns"
    const val PRIVACY_PERMISSIONS = "privacy_permissions"
    const val PRIVACY_REPORT = "privacy_report"
    const val PRIVACY_CLEAR = "privacy_clear"
    const val SEARCH = "search"
    const val TABS = "tabs"
    const val APPEARANCE = "appearance"
    const val WEBSITES = "websites"
    const val DOWNLOADS = "downloads"
    const val PASSWORDS = "passwords"
    const val MEDIA = "media"
    const val ACCESSIBILITY = "accessibility"
    const val DATA = "data"
    const val ADVANCED = "advanced"
    const val DEVELOPER = "developer"
    const val RESET = "reset"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
    const val PRIVACY_CENTRE = "privacy_centre"
    const val TABS_BEHAVIOUR = "tabs_behaviour"
    const val SEARCH_ENGINES = "search_engines"
    const val COMPATIBILITY = "compatibility"
    const val DIAGNOSTICS = "diagnostics"
    const val PASSWORD_VAULT = "passwords_vault"

    const val SOURCE_URL = "https://github.com/palashsuryavanshi/LibreWolfAndroid"

    private val screens: List<Screen> = listOf(
        root(), privacy(), tracking(), fingerprinting(), cookies(), https(), dns(),
        permissions(), report(), clearData(), search(), tabsBehaviour(), tabs(),
        appearance(), websites(), downloads(), passwords(), media(), accessibility(),
        data(), advanced(), developer(), reset(), about(), licences(), privacyCentre(),
        searchEngines(), compatibility(), diagnostics(),
    )

    fun all(): List<Screen> = screens

    fun screen(id: String): Screen? = screens.firstOrNull { it.id == id }

    /**
     * The screen for an id, with the site being troubleshot when it applies.
     *
     * Only the compatibility page takes a parameter, so it is resolved here
     * instead of being stored twice in the list.
     */
    fun render(id: String, site: String?): Screen? = when (id) {
        COMPATIBILITY -> site?.takeIf { it.isNotBlank() }?.let { compatibility(it) } ?: screen(id)
        else -> screen(id)
    }

    // ---- root ----

    private fun root() = screen(ROOT, "Settings", "home", "main") {
        sections {
            section {
                nav(string(R.string.cat_privacy), PRIVACY, string(R.string.cat_privacy_sub), R.drawable.ic_shield) 
                nav(string(R.string.cat_search), SEARCH, string(R.string.cat_search_sub), R.drawable.ic_search) 
                nav(string(R.string.cat_tabs), TABS, string(R.string.cat_tabs_sub), R.drawable.ic_tabs) 
                nav(string(R.string.cat_appearance), APPEARANCE, string(R.string.cat_appearance_sub), R.drawable.ic_palette) 
                nav(string(R.string.cat_websites), WEBSITES, string(R.string.cat_websites_sub), R.drawable.ic_globe) 
                nav(string(R.string.cat_downloads), DOWNLOADS, string(R.string.cat_downloads_sub), R.drawable.ic_dl) 
                nav(string(R.string.cat_passwords), PASSWORDS, string(R.string.cat_passwords_sub), R.drawable.ic_key) 
                nav(string(R.string.cat_media), MEDIA, string(R.string.cat_media_sub), R.drawable.ic_play) 
                nav(string(R.string.cat_accessibility), ACCESSIBILITY, string(R.string.cat_accessibility_sub), R.drawable.ic_access) 
                nav(string(R.string.cat_data), DATA, string(R.string.cat_data_sub), R.drawable.ic_storage) 
                nav(string(R.string.cat_advanced), ADVANCED, string(R.string.cat_advanced_sub), R.drawable.ic_gear) 
                nav(string(R.string.cat_about), ABOUT, string(R.string.cat_about_sub), R.drawable.ic_about) 
            }
            section(footer = string(R.string.settings_search_hint)) {
                nav(string(R.string.privacy_centre), PRIVACY_CENTRE, string(R.string.privacy_centre_sub), R.drawable.ic_shield) 
            }
        }
    }

    // ---- privacy & security ----

    private fun privacy() = screen(PRIVACY, "Privacy & Security", "protection", "privacy") {
        sections {
            section(string(R.string.sec_protection)) {
                nav(string(R.string.settings_tracking_protection), PRIVACY_TRACKING, trackingLabel(), null) 
                nav(string(R.string.settings_fingerprinting), PRIVACY_FINGERPRINTING, fingerprintingLabel(), null) 
                nav(string(R.string.settings_cookies_site_data), PRIVACY_COOKIES, cookiesLabel(), null) 
                nav(string(R.string.settings_https), PRIVACY_HTTPS, httpsLabel(), null) 
                nav(string(R.string.settings_secure_dns), PRIVACY_DNS, dohLabel(), null) 
                nav(string(R.string.settings_permissions), PRIVACY_PERMISSIONS, string(R.string.settings_permissions_sub), null) 
            }
            section(string(R.string.sec_privacy)) {
                nav(string(R.string.privacy_report), PRIVACY_REPORT, string(R.string.privacy_report_sub), null) 
                action(string(R.string.settings_delete_now), { clearDialog(it) }, string(R.string.settings_delete_now_sub), null) 
                action(string(R.string.settings_delete_quit), { autoClearDialog(it) }, string(R.string.settings_delete_quit_sub), null) 
            }
            section(string(R.string.sec_security), footer = string(R.string.restart_note)) {
                choice(
                    string(R.string.settings_safe_browsing),
                    listOf(string(R.string.off), string(R.string.protection_enabled)),
                    { store.safeBrowsingMode.coerceIn(0, 1) },
                    { _, which -> store.safeBrowsingMode = which },
                    message = string(R.string.safe_browsing_message),
                    onChanged = { refresh() },
                )
            }
        }
    }

    private fun tracking() = screen(PRIVACY_TRACKING, "Tracking protection", "etp", "trackers") {
        sections {
            section(string(R.string.sec_protection_level)) {
                choice(
                    string(R.string.settings_etp),
                    listOf(string(R.string.etp_standard), string(R.string.etp_strict)),
                    { if (store.etpStrict) 1 else 0 },
                    { _, which -> store.etpStrict = which == 1 },
                    message = string(R.string.etp_message),
                    onChanged = { toast(string(R.string.restart_note)) },
                )
                toggle(
                    string(R.string.settings_block_social),
                    { store.blockSocialTrackers },
                    { _, value -> store.blockSocialTrackers = value },
                    subtitle = string(R.string.settings_block_social_sub),
                    onChanged = { toast(string(R.string.restart_note)) },
                )
            }
            section(footer = string(R.string.etp_covered)) {
                info(string(R.string.etp_covered_title), string(R.string.etp_covered_body))
            }
        }
    }

    private fun fingerprinting() = screen(PRIVACY_FINGERPRINTING, "Fingerprinting", "fingerprint", "resist") {
        sections {
            section(footer = string(R.string.fingerprinting_message)) {
                choice(
                    string(R.string.settings_fingerprinting),
                    listOf(string(R.string.fp_standard), string(R.string.fp_strict), string(R.string.off)),
                    { store.fingerprintingMode.coerceIn(0, 2) },
                    { _, which -> store.fingerprintingMode = which },
                    onChanged = { toast(string(R.string.restart_note)) },
                )
            }
            section(string(R.string.sec_limits)) {
                info(string(R.string.fp_limits_title), string(R.string.fp_limits_body))
            }
        }
    }

    private fun cookies() = screen(PRIVACY_COOKIES, "Cookies & site data", "cookies", "third party", "storage") {
        sections {
            section(string(R.string.sec_cookie_policy)) {
                choice(
                    string(R.string.settings_third_party_cookies),
                    listOf(
                        string(R.string.cookies_block_third_party),
                        string(R.string.cookies_allow),
                        string(R.string.cookies_block_all),
                    ),
                    { if (store.blockAllCookies) 2 else if (store.blockThirdPartyCookies) 0 else 1 },
                    { _, which ->
                        store.blockAllCookies = which == 2
                        store.blockThirdPartyCookies = which != 1
                    },
                    message = string(R.string.cookies_message),
                    onChanged = { toast(string(R.string.restart_note)) },
                )
            }
            section(string(R.string.sec_stored)) {
                nav(string(R.string.settings_site), PRIVACY_PERMISSIONS, string(R.string.settings_site_sub), null) 
                action(string(R.string.clear_cookies), { clearOne(it, SettingsClear.COOKIES) }, null, null) 
                action(string(R.string.clear_cache), { clearOne(it, SettingsClear.CACHE) }, null, null) 
                action(string(R.string.clear_site_data), { clearOne(it, SettingsClear.SITE_DATA) }, null, null) 
            }
        }
    }

    private fun https() = screen(PRIVACY_HTTPS, "HTTPS", "https", "certificate", "tls") {
        sections {
            section(footer = string(R.string.https_message)) {
                choice(
                    string(R.string.settings_https),
                    listOf(string(R.string.https_off), string(R.string.https_private), string(R.string.https_all)),
                    { store.httpsMode.coerceIn(0, 2) },
                    { _, which -> store.httpsMode = which },
                    onChanged = { toast(string(R.string.restart_note)) },
                )
            }
            section(string(R.string.sec_never)) {
                info(string(R.string.https_never_title), string(R.string.https_never_body))
            }
        }
    }

    private fun dns() = screen(PRIVACY_DNS, "Secure DNS", "doh", "dns", "resolver") {
        sections {
            section(footer = string(R.string.dns_privacy_note)) {
                choice(
                    string(R.string.settings_doh),
                    listOf(string(R.string.doh_off), string(R.string.doh_default), string(R.string.doh_max)),
                    { store.dohMode.coerceIn(0, 2) },
                    { _, which -> store.dohMode = which },
                    onChanged = { toast(string(R.string.restart_note)) },
                )
                choice(
                    string(R.string.settings_doh_provider),
                    LibreWolfDefaults.DOH_PROVIDERS.map { it.name },
                    { store.dohProvider.coerceIn(0, LibreWolfDefaults.DOH_PROVIDERS.lastIndex) },
                    { _, which -> store.dohProvider = which },
                    onChanged = { toast(string(R.string.restart_note)) },
                )
            }
        }
    }

    private fun permissions() = screen(PRIVACY_PERMISSIONS, "Permissions", "camera", "microphone", "location", "notifications") {
        sections {
            section {
                action(string(R.string.settings_site), { activity_(Intent(activity, SitePermissionsActivity::class.java)) }, string(R.string.settings_site_sub), null) 
                action(string(R.string.android_permissions), { activity_(appDetails()) }, string(R.string.android_permissions_sub), null) 
                action(string(R.string.settings_notifications), { askForNotifications() }, string(R.string.settings_notifications_ask_sub), null)
            }
            section(footer = string(R.string.permissions_flow)) {
                info(string(R.string.permissions_default_title), string(R.string.permissions_default_body))
            }
        }
    }

    private fun report() = screen(PRIVACY_REPORT, "Privacy report", "trackers", "threats", "statistics") {
        sections {
            section(string(R.string.sec_blocked)) {
                info(string(R.string.stat_trackers), store.cumulativeTrackers.toString())
                info(string(R.string.stat_threats), store.cumulativeThreats.toString())
            }
            section(string(R.string.sec_stored)) {
                info(string(R.string.stat_history), history.all().size.toString())
                info(string(R.string.stat_bookmarks), bookmarks.all().size.toString())
                info(string(R.string.stat_sites_with_permissions), sitePermissions.all().size.toString())
            }
            section(footer = string(R.string.report_limits)) {
                action(string(R.string.settings_delete_now), { clearDialog(it) }, null, null) 
            }
        }
    }

    private fun clearData() = screen(PRIVACY_CLEAR, "Clear browsing data", "clear", "delete", "cookies") {
        sections {
            section {
                action(string(R.string.settings_delete_now), { clearDialog(it) }, string(R.string.settings_delete_now_sub), null) 
            }
            section(footer = string(R.string.clear_categories_note)) {
                info(string(R.string.clear_cache_note_title), string(R.string.clear_cache_note_body))
            }
        }
    }

    // ---- search ----

    private fun search() = screen(SEARCH, "Search", "engine", "suggestions", "address bar") {
        sections {
            section {
                nav(string(R.string.settings_search), SEARCH_ENGINES, store.engineFor(false).name, null) 
            }
            section(string(R.string.sec_suggestions), footer = string(R.string.suggestions_privacy)) {
                toggle(string(R.string.settings_suggestions), { store.searchSuggestions }, { _, v -> store.searchSuggestions = v }, onChanged = { refresh() }) 
                toggle(string(R.string.settings_suggestions_private), { store.searchSuggestionsPrivate }, { _, v -> store.searchSuggestionsPrivate = v }, onChanged = { refresh() }) 
                toggle(string(R.string.settings_show_history), { store.searchHistory }, { _, v -> store.searchHistory = v }, onChanged = { refresh() }) 
                toggle(string(R.string.settings_show_bookmarks), { store.searchBookmarks }, { _, v -> store.searchBookmarks = v }, onChanged = { refresh() }) 
                toggle(string(R.string.settings_clipboard_suggestions), { store.showClipboardSuggestions }, { _, v -> store.showClipboardSuggestions = v }, onChanged = { refresh() }) 
                toggle(string(R.string.settings_voice_search), { store.voiceSearch }, { _, v -> store.voiceSearch = v }, onChanged = { refresh() }) 
                toggle(string(R.string.settings_autocomplete_urls), { store.autocompleteUrls }, { _, v -> store.autocompleteUrls = v }, onChanged = { refresh() }) 
            }
            section(string(R.string.sec_search_privacy), footer = string(R.string.search_privacy_note)) {
                info(string(R.string.search_privacy_title), string(R.string.search_privacy_body))
            }
        }
    }

    private fun searchEngines() = screen(SEARCH_ENGINES, "Search engine", "google", "duckduckgo", "bing", "custom") {
        sections {
            section(footer = string(R.string.search_engines_note)) {
                action(string(R.string.settings_search), { activity_(Intent(activity, SearchEnginesActivity::class.java)) }, store.engineFor(false).name) 
                action(string(R.string.settings_default_search), { activity_(Intent(activity, DefaultSearchActivity::class.java)) }, string(R.string.settings_default_search_sub)) 
            }
        }
    }

    // ---- tabs ----

    private fun tabs() = screen(TABS, "Tabs", "groups", "session", "close") {
        sections {
            section {
                nav(string(R.string.settings_tabs), TABS_BEHAVIOUR, tabViewLabel(), null) 
            }
            section(string(R.string.sec_sessions)) {
                toggle(string(R.string.settings_tab_groups), { store.tabGroups }, { _, v -> store.tabGroups = v }, onChanged = { refresh() })
                toggle(string(R.string.settings_close_tabs), { store.closeTabsAfterDays > 0 }, { _, v -> store.closeTabsAfterDays = if (v) 7 else 0 }, subtitle = string(R.string.settings_close_tabs_sub), onChanged = { refresh() })
                toggle(string(R.string.settings_move_inactive), { store.moveOldTabsToInactive }, { _, v -> store.moveOldTabsToInactive = v }, onChanged = { refresh() })
            }
        }
    }

    private fun tabsBehaviour() = screen(TABS_BEHAVIOUR, "Tab behaviour", "layout", "grid", "list", "close", "startup") {
        sections {
            section {
                choice(
                    string(R.string.settings_tabs),
                    listOf(string(R.string.view_grid), string(R.string.view_list)),
                    { if (store.tabViewColumns == 1) 1 else 0 },
                    { _, which -> store.tabViewColumns = if (which == 1) 1 else 2 },
                    onChanged = { refresh() },
                )
            }
            section(string(R.string.sec_new_tabs), footer = string(R.string.new_tabs_note)) {
                toggle(string(R.string.settings_new_tabs_foreground), { store.newTabsInForeground }, { _, v -> store.newTabsInForeground = v }, string(R.string.settings_new_tabs_foreground_sub), onChanged = { refresh() })
                toggle(string(R.string.settings_confirm_close), { store.confirmCloseTabs }, { _, v -> store.confirmCloseTabs = v }, string(R.string.settings_confirm_close_sub), onChanged = { refresh() })
            }
            section(string(R.string.sec_sessions), footer = string(R.string.tabs_note)) {
                info(string(R.string.tabs_restore_title), string(R.string.tabs_restore_body))
            }
        }
    }

    // ---- appearance ----

    private fun appearance() = screen(APPEARANCE, "Appearance", "theme", "new tab", "dark", "homepage", "toolbar") {
        sections {
            section {
                choice(
                    string(R.string.settings_theme),
                    listOf(string(R.string.theme_system), string(R.string.theme_light), string(R.string.theme_dark)),
                    { store.themeMode.coerceIn(0, 2) },
                    { _, which -> store.themeMode = which },
                    onChanged = { BrowserTheme.apply(activity); refresh() },
                )
            }
            section(string(R.string.sec_new_tab)) {
                toggle(string(R.string.settings_show_recent), { store.showRecentSearches }, { _, v -> store.showRecentSearches = v }, onChanged = { refresh() })
                action(string(R.string.settings_customise), { homepageDialog(it) }, homepageLabel()) 
            }
            section(string(R.string.sec_limits)) {
                // The address bar is at the bottom of this layout and the engine
                // composites it, so its position is not a switch the shell can
                // honour. Saying so beats a control that does nothing.
                info(string(R.string.settings_toolbar), string(R.string.settings_toolbar_body))
            }
            section(string(R.string.sec_sites), footer = string(R.string.restart_note)) {
                toggle(string(R.string.settings_desktop_site), { store.desktopMode }, { _, v -> store.desktopMode = v }, string(R.string.settings_desktop_site_sub), onChanged = { toast(string(R.string.restart_note)) })
                toggle(string(R.string.settings_spoof_desktop), { store.spoofDesktopUserAgent }, { _, v -> store.spoofDesktopUserAgent = v }, string(R.string.settings_spoof_desktop_sub), onChanged = { toast(string(R.string.restart_note)) })
            }
        }
    }

    // ---- websites ----

    private fun websites() = screen(WEBSITES, "Websites", "javascript", "popups", "autoplay", "notifications") {
        sections {
            section(string(R.string.sec_behaviour), footer = string(R.string.restart_note)) {
                toggle(
                    string(R.string.settings_javascript),
                    { store.javascriptEnabled },
                    { _, v -> store.javascriptEnabled = v },
                    if (store.javascriptEnabled) string(R.string.enabled) else string(R.string.disabled),
                    onChanged = { toast(string(R.string.restart_note)); refresh() },
                )
                toggle(string(R.string.settings_block_popups), { store.blockPopups }, { _, v -> store.blockPopups = v }, string(R.string.settings_block_popups_sub)) 
                choice(
                    string(R.string.settings_autoplay),
                    listOf(string(R.string.perm_ask), string(R.string.autoplay_allow), string(R.string.autoplay_block)),
                    { store.autoplayMode.coerceIn(0, 2) },
                    { _, which -> store.autoplayMode = which },
                    message = string(R.string.autoplay_message),
                    onChanged = { refresh() },
                )
                choice(
                    string(R.string.settings_notifications),
                    listOf(string(R.string.perm_ask), string(R.string.autoplay_allow), string(R.string.autoplay_block)),
                    { store.notificationMode.coerceIn(0, 2) },
                    { _, which -> store.notificationMode = which },
                    onChanged = { refresh() },
                )
            }
            section(string(R.string.sec_per_site)) {
                nav(string(R.string.settings_site), PRIVACY_PERMISSIONS, string(R.string.settings_site_sub), null) 
            }
        }
    }

    // ---- downloads ----

    private fun downloads() = screen(DOWNLOADS, "Downloads", "location", "folder", "notification") {
        sections {
            section {
                text(
                    string(R.string.settings_download_location),
                    { if (store.downloadFolder.isBlank()) string(R.string.downloads_folder_default) else store.downloadFolder },
                    { _, value -> store.downloadFolder = value },
                    message = string(R.string.download_folder_message),
                )
                toggle(string(R.string.settings_download_notifications), { store.downloadNotifications }, { _, v -> store.downloadNotifications = v }, onChanged = { refresh() })
                toggle(string(R.string.settings_dangerous_downloads), { store.warnRiskyDownloads }, { _, v -> store.warnRiskyDownloads = v }, string(R.string.settings_dangerous_downloads_sub)) 
            }
            section {
                action(string(R.string.clear_downloads), { clearOne(it, SettingsClear.DOWNLOADS) }, null, null) 
            }
        }
    }

    // ---- passwords ----

    private fun passwords() = screen(PASSWORDS, "Passwords & Autofill", "passwords", "autofill", "vault", "passkeys") {
        sections {
            section {
                action(string(R.string.settings_passwords), { activity_(Intent(activity, PasswordsActivity::class.java)) }, string(R.string.settings_passwords_sub), null) 
                action(string(R.string.settings_autofill), { autofillInfo(it) }, string(R.string.settings_autofill_sub), null) 
            }
            section(footer = string(R.string.passkeys_note)) {
                info(string(R.string.passkeys_title), string(R.string.passkeys_body))
            }
        }
    }

    // ---- media ----

    private fun media() = screen(MEDIA, "Media", "pip", "background audio", "autoplay") {
        sections {
            section(footer = string(R.string.media_note)) {
                nav(string(R.string.settings_autoplay), WEBSITES, autoplayLabel(), null) 
                toggle(string(R.string.settings_background_media), { store.backgroundMedia }, { _, v -> store.backgroundMedia = v }, string(R.string.settings_background_media_sub)) 
                toggle(string(R.string.settings_media_notifications), { store.mediaNotifications }, { _, v -> store.mediaNotifications = v }, string(R.string.settings_media_notifications_sub)) 
                toggle(string(R.string.settings_pip), { store.pictureInPicture }, { _, v -> store.pictureInPicture = v }, string(R.string.settings_pip_sub)) 
            }
        }
    }

    // ---- accessibility ----

    private fun accessibility() = screen(ACCESSIBILITY, "Accessibility", "text size", "zoom", "motion", "talkback") {
        sections {
            section(footer = string(R.string.font_size_note)) {
                slider(
                    string(R.string.settings_min_font_size),
                    0..24,
                    { store.minimumFontSize },
                    { _, value -> store.minimumFontSize = value },
                    format = { if (it == 0) string(R.string.font_size_none) else it.toString() },
                )
                action(string(R.string.settings_accessibility), { activity_(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, string(R.string.settings_accessibility_sub), null) 
                info(string(R.string.settings_reduce_motion), reduceMotionLabel()) 
            }
            section(footer = string(R.string.zoom_note)) {
                info(string(R.string.settings_page_zoom), string(R.string.zoom_unavailable))
                info(string(R.string.settings_reader_mode), string(R.string.reader_mode_unavailable))
            }
        }
    }

    // ---- data & storage ----

    private fun data() = screen(DATA, "Data & Storage", "cache", "site data", "storage", "clear") {
        sections {
            section(string(R.string.sec_measured), footer = string(R.string.storage_note)) {
                info(string(R.string.storage_profile), Format.bytes(activity, profileSize()))
                info(string(R.string.storage_downloads), Format.bytes(activity, downloadSize()))
                info(string(R.string.storage_sites), sitePermissions.all().size.toString())
            }
            section(string(R.string.sec_clear_what)) {
                action(string(R.string.clear_cache), { clearOne(it, SettingsClear.CACHE) }, null, null) 
                action(string(R.string.clear_site_data), { clearOne(it, SettingsClear.SITE_DATA) }, null, null) 
                action(string(R.string.clear_cookies), { clearOne(it, SettingsClear.COOKIES) }, null, null) 
                action(string(R.string.settings_delete_now), { clearDialog(it) }, string(R.string.settings_delete_now_sub)) 
            }
            section {
                toggle(string(R.string.settings_delete_quit), { store.sanitizeOnShutdown }, { _, v -> store.sanitizeOnShutdown = v }, string(R.string.settings_delete_quit_sub)) 
            }
        }
    }

    // ---- advanced ----

    private fun advanced() = screen(ADVANCED, "Advanced", "network", "developer", "reset", "compatibility") {
        sections {
            section {
                nav(string(R.string.settings_secure_dns), PRIVACY_DNS, dohLabel(), null) 
                nav(string(R.string.settings_compatibility), COMPATIBILITY, string(R.string.settings_compatibility_sub), null) 
                nav(string(R.string.settings_developer), DEVELOPER, string(R.string.settings_developer_sub), null) 
                nav(string(R.string.settings_reset), RESET, string(R.string.settings_reset_sub), null) 
            }
            section(footer = string(R.string.advanced_limits)) {
                info(string(R.string.advanced_limits_title), string(R.string.advanced_limits_body))
            }
        }
    }

    /**
     * Web compatibility, for the site in front.
     *
     * The site arrives as an intent extra. When it is there the page is about
     * that one site: what it has been allowed, and the two switches that most
     * often fix a site that misbehaves. Without it the page is the general
     * version, which is still reachable from Advanced.
     */
    private fun compatibility(site: String? = null) =
        screen(COMPATIBILITY, "Web compatibility", "site", "troubleshoot", "broken") {
            val authority = site?.let { SitePermissionIndex.authorityOf(it) }
            val entries = authority
                ?.let { wanted -> SitePermissionIndex.entries(activity).filter { it.authority == wanted } }
                .orEmpty()
            sections {
                if (authority != null) {
                    section(footer = string(R.string.compatibility_site_note)) {
                        info(string(R.string.compatibility_this_site), authority, null, R.drawable.ic_globe)
                        entries.forEach { entry ->
                            action(
                                SitePermissionIndex.label(this@screen.activity, entry.permission) + " · " +
                                    SitePermissionIndex.valueLabel(this@screen.activity, entry.value),
                                { flipDecision(it, authority, entry.permission) },
                            )
                        }
                        if (entries.isEmpty()) {
                            info(string(R.string.compatibility_no_decisions), string(R.string.compatibility_no_decisions_body))
                        }
                    }
                    section {
                        toggle(string(R.string.settings_desktop_site), { store.desktopMode }, { _, v -> store.desktopMode = v }, string(R.string.settings_desktop_site_sub), onChanged = { toast(string(R.string.restart_note)) })
                        toggle(string(R.string.settings_spoof_desktop), { store.spoofDesktopUserAgent }, { _, v -> store.spoofDesktopUserAgent = v }, string(R.string.settings_spoof_desktop_sub), onChanged = { toast(string(R.string.restart_note)) })
                    }
                    section {
                        nav(string(R.string.settings_site), PRIVACY_PERMISSIONS, string(R.string.settings_site_sub))
                    }
                } else {
                    section {
                        nav(string(R.string.settings_site), PRIVACY_PERMISSIONS, string(R.string.settings_site_sub))
                        toggle(string(R.string.settings_desktop_site), { store.desktopMode }, { _, v -> store.desktopMode = v }, string(R.string.settings_desktop_site_sub), onChanged = { toast(string(R.string.restart_note)) })
                    }
                    section(footer = string(R.string.compatibility_note)) {
                        info(string(R.string.compatibility_title), string(R.string.compatibility_body))
                    }
                }
            }
        }

    /**
     * Flips one decision for the site being troubleshot.
     *
     * Allow and block swap, and a capability with no decision yet starts
     * allowed, because the user arrived here because the site is not working.
     */
    private fun flipDecision(ctx: SettingsContext, authority: String, permission: Int) {
        val current = SitePermissionIndex.entries(ctx.activity).firstOrNull {
            it.authority == authority && it.permission == permission
        }?.value
        val next = when (current) {
            ContentPermission.VALUE_ALLOW -> ContentPermission.VALUE_DENY
            ContentPermission.VALUE_DENY -> ContentPermission.VALUE_ALLOW
            else -> ContentPermission.VALUE_ALLOW
        }
        SitePermissionIndex.set(ctx.activity, authority, permission, next)
        ctx.repaint()
        ctx.toast("${SitePermissionIndex.label(ctx.activity, permission)}: ${SitePermissionIndex.valueLabel(ctx.activity, next)}")
    }

    private fun developer() = screen(DEVELOPER, "Developer options", "debug", "remote") {
        sections {
            section {
                nav(string(R.string.diagnostics), DIAGNOSTICS, string(R.string.settings_diagnostics_sub), null) 
                if (BuildConfig.DEBUG) {
                    toggle(string(R.string.settings_remote), { store.remoteDebugging }, { _, v -> store.remoteDebugging = v }, string(R.string.settings_remote_sub)) 
                }
                action(string(R.string.settings_clear_debug_data), { clearOne(it, SettingsClear.COOKIES) }, string(R.string.settings_clear_debug_data_sub)) 
            }
            section(footer = if (BuildConfig.DEBUG) null else string(R.string.developer_hidden)) {
                info(string(R.string.settings_developer), string(R.string.developer_release_note))
            }
        }
    }

    private fun diagnostics() = screen(DIAGNOSTICS, "Diagnostics", "engine", "memory", "refresh rate") {
        sections {
            section {
                action(string(R.string.diagnostics), { activity_(Intent(activity, DiagnosticsActivity::class.java)) }, string(R.string.settings_diagnostics_sub), null) 
            }
        }
    }

    private fun reset() = screen(RESET, "Reset", "reset", "defaults") {
        sections {
            section(footer = string(R.string.reset_note)) {
                action(string(R.string.settings_reset), { confirmReset(it) }, string(R.string.settings_reset_sub), null) 
            }
        }
    }

    // ---- about ----

    private fun about() = screen(ABOUT, "About", "version", "licences", "privacy") {
        sections {
            section {
                info(string(R.string.about_browser), BuildConfig.VERSION_NAME, "(${BuildConfig.VERSION_CODE})", R.drawable.librewolf_logo) 
                info(string(R.string.about_engine), BuildConfig.UPSTREAM_FIREFOX, string(R.string.about_engine_sub), R.drawable.ic_globe) 
                info(string(R.string.about_librefox_release), BuildConfig.LIBREWOLF_RELEASE, null, R.drawable.ic_shield) 
            }
            section {
                nav(string(R.string.about_licences), LICENSES, string(R.string.about_licences_sub), null) 
                action(string(R.string.about_source), { activity_(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL))) }, SOURCE_URL) 
                action(string(R.string.about_report), { reportProblem(it) }, string(R.string.about_report_sub), null) 
                action(string(R.string.about_privacy_policy), { privacyPolicy(it) }, string(R.string.about_privacy_policy_sub), null) 
            }
        }
    }

    private fun licences() = screen(LICENSES, "Open source licences", "licence", "license", "notices") {
        sections {
            section {
                info(string(R.string.licence_app), "GPL-3.0", null, R.drawable.librewolf_logo) 
                info("GeckoView / Firefox", "MPL-2.0", null, R.drawable.ic_globe) 
                info("AndroidX, Material Components", "Apache-2.0", null, null) 
            }
            section(footer = string(R.string.licence_note)) {
                action(string(R.string.about_source), { activity_(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL))) }, SOURCE_URL) 
            }
        }
    }

    // ---- privacy centre ----

    private fun privacyCentre() = screen(PRIVACY_CENTRE, "Privacy centre", "summary", "status", "overview") {
        sections {
            section(string(R.string.centre_protection), footer = string(R.string.centre_note)) {
                info(string(R.string.centre_tracking), if (store.etpStrict) string(R.string.etp_strict) else string(R.string.etp_standard))
                info(string(R.string.centre_fingerprinting), fingerprintingLabel())
                info(string(R.string.centre_cookies), cookiesLabel())
                info(string(R.string.centre_https), httpsLabel())
                info(string(R.string.centre_safe_browsing), if (store.safeBrowsingMode == 0) string(R.string.off) else string(R.string.protection_enabled))
            }
            section(string(R.string.centre_blocked)) {
                info(string(R.string.stat_trackers), store.cumulativeTrackers.toString())
                info(string(R.string.stat_threats), store.cumulativeThreats.toString())
            }
            section(string(R.string.centre_data)) {
                info(string(R.string.stat_history), history.all().size.toString())
                info(string(R.string.stat_sites_with_permissions), sitePermissions.all().size.toString())
                info(string(R.string.storage_profile), Format.bytes(activity, profileSize()))
            }
            section {
                action(string(R.string.settings_delete_now), { clearDialog(it) }, string(R.string.settings_delete_now_sub), null) 
                nav(string(R.string.settings_site), PRIVACY_PERMISSIONS, string(R.string.settings_site_sub), null) 
            }
        }
    }

    // ---- labels ----

    private fun SettingsContext.trackingLabel() =
        if (store.etpStrict) string(R.string.etp_strict) else string(R.string.etp_standard)

    private fun SettingsContext.fingerprintingLabel() = when (store.fingerprintingMode) {
        1 -> string(R.string.fp_strict)
        2 -> string(R.string.off)
        else -> string(R.string.fp_standard)
    }

    private fun SettingsContext.cookiesLabel() = when {
        store.blockAllCookies -> string(R.string.cookies_block_all)
        store.blockThirdPartyCookies -> string(R.string.cookies_block_third_party)
        else -> string(R.string.cookies_allow)
    }

    private fun SettingsContext.httpsLabel() = when (store.httpsMode) {
        1 -> string(R.string.https_private)
        2 -> string(R.string.https_all)
        else -> string(R.string.https_off)
    }

    private fun SettingsContext.dohLabel() = when (store.dohMode) {
        1 -> string(R.string.doh_default)
        2 -> string(R.string.doh_max)
        else -> string(R.string.doh_off)
    }

    private fun SettingsContext.autoplayLabel() = when (store.autoplayMode) {
        1 -> string(R.string.autoplay_allow)
        2 -> string(R.string.autoplay_block)
        else -> string(R.string.perm_ask)
    }

    private fun SettingsContext.tabViewLabel() =
        if (store.tabViewColumns == 1) string(R.string.view_list) else string(R.string.view_grid)

    private fun SettingsContext.homepageLabel() =
        if (store.homepage == MainActivity.HOME_START) string(R.string.homepage_start) else store.homepage

    private fun SettingsContext.reduceMotionLabel(): String {
        // Read through the settings table rather than AccessibilityManager:
        // the manager's reduce-motion accessor is not public on every release,
        // and the value the user sees in Android's own settings is this one.
        val on = runCatching {
            android.provider.Settings.Global.getFloat(
                activity.contentResolver,
                "transition_animation_scale",
                1f,
            ) == 0f
        }.getOrDefault(false)
        return if (on) string(R.string.on) else string(R.string.off)
    }

    private fun SettingsContext.profileSize(): Long = runCatching {
        java.io.File(activity.applicationInfo.dataDir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun SettingsContext.downloadSize(): Long =
        downloads.all().sumOf { it.bytesDownloaded.coerceAtLeast(0L) }

    private fun SettingsContext.appDetails(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", activity.packageName, null))

    /**
     * Re-asks for the notification permission from a page the user navigated to
     * on purpose. Sites never trigger this: a denial is remembered so the app
     * stops putting the system dialog in front of the user uninvited.
     */
    private fun SettingsContext.askForNotifications() {
        (activity as? SettingsActivity)?.requestNotificationPermission() ?: activity_(appDetails())
    }

    // ---- dialogs shared by several screens ----

    fun clearDialog(ctx: SettingsContext) {
        val labels = SettingsClear.labels(ctx)
        val selected = BooleanArray(labels.size)
        AlertDialog.Builder(ctx.activity)
            .setTitle(R.string.clear_browsing_data)
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> SettingsClear.apply(ctx, selected) }
            .show()
    }

    fun clearOne(ctx: SettingsContext, which: Int) {
        val labels = SettingsClear.labels(ctx)
        val selected = BooleanArray(labels.size)
        selected[which] = true
        ctx.confirm(labels[which], ctx.string(R.string.clear_confirm_message), ctx.string(R.string.delete), destructive = true) {
            SettingsClear.apply(ctx, selected)
        }
    }

    fun autoClearDialog(ctx: SettingsContext) {
        val items = arrayOf(
            ctx.string(R.string.clear_history),
            ctx.string(R.string.clear_cookies),
            ctx.string(R.string.clear_site_data),
            ctx.string(R.string.clear_cache),
            ctx.string(R.string.clear_downloads),
            ctx.string(R.string.clear_bookmarks),
        )
        val chosen = booleanArrayOf(
            ctx.store.sanitizeHistory,
            ctx.store.sanitizeCookies,
            ctx.store.sanitizeSiteData,
            ctx.store.sanitizeCache,
            ctx.store.sanitizeDownloads,
            ctx.store.sanitizeBookmarks,
        )
        AlertDialog.Builder(ctx.activity)
            .setTitle(R.string.settings_delete_quit)
            .setMultiChoiceItems(items, chosen) { _, which, checked -> chosen[which] = checked }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                ctx.store.sanitizeHistory = chosen[0]
                ctx.store.sanitizeCookies = chosen[1]
                ctx.store.sanitizeSiteData = chosen[2]
                ctx.store.sanitizeCache = chosen[3]
                ctx.store.sanitizeDownloads = chosen[4]
                ctx.store.sanitizeBookmarks = chosen[5]
                ctx.toast(ctx.string(R.string.restart_note))
            }
            .show()
    }

    fun homepageDialog(ctx: SettingsContext) {
        val isCustom = ctx.store.homepage != MainActivity.HOME_START
        val options = arrayOf(ctx.string(R.string.homepage_start), ctx.string(R.string.homepage_custom))
        AlertDialog.Builder(ctx.activity)
            .setTitle(R.string.settings_homepage)
            .setSingleChoiceItems(options, if (isCustom) 1 else 0) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    ctx.store.homepage = MainActivity.HOME_START
                    ctx.refresh()
                } else {
                    val input = android.widget.EditText(ctx.activity).apply {
                        hint = "https://…"
                        setText(if (isCustom) ctx.store.homepage else "")
                    }
                    AlertDialog.Builder(ctx.activity)
                        .setTitle(R.string.settings_homepage)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val value = input.text.toString().trim()
                            if (value.isNotEmpty()) ctx.store.homepage = value
                            ctx.refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun autofillInfo(ctx: SettingsContext) {
        AlertDialog.Builder(ctx.activity)
            .setTitle(R.string.settings_autofill)
            .setMessage(R.string.autofill_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun confirmReset(ctx: SettingsContext) {
        ctx.confirm(ctx.string(R.string.settings_reset), ctx.string(R.string.reset_confirm), ctx.string(R.string.settings_reset)) {
            // The theme and the download folder are choices about this device,
            // not preferences about the web, and losing them to a reset would be
            // a surprise rather than a clean slate.
            val theme = ctx.store.themeMode
            val downloadFolder = ctx.store.downloadFolder
            ctx.store.resetToDefaults()
            ctx.store.themeMode = theme
            ctx.store.downloadFolder = downloadFolder
            ctx.toast(ctx.string(R.string.reset_done))
            ctx.refresh()
        }
    }

    fun reportProblem(ctx: SettingsContext) {
        ctx.activity_(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$SOURCE_URL/issues")))
    }

    fun privacyPolicy(ctx: SettingsContext) {
        AlertDialog.Builder(ctx.activity)
            .setTitle(R.string.about_privacy_policy)
            .setMessage(R.string.privacy_policy_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
