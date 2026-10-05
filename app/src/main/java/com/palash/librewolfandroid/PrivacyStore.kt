package com.palash.librewolfandroid

import android.content.Context
import androidx.core.content.edit

/** Persists LibreWolf-style privacy prefs. Defaults mirror source/settings/librewolf.cfg. */
class PrivacyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var blockThirdPartyCookies: Boolean
        get() = prefs.getBoolean(K_3P, LibreWolfDefaults.BLOCK_THIRD_PARTY_COOKIES)
        set(v) = prefs.edit { putBoolean(K_3P, v) }

    var sanitizeOnShutdown: Boolean
        get() = prefs.getBoolean(K_SAN, LibreWolfDefaults.SANITIZE_ON_SHUTDOWN)
        set(v) = prefs.edit { putBoolean(K_SAN, v) }

    /** 0 = off, 1 = private tabs only, 2 = all tabs (LibreWolf default). */
    var httpsMode: Int
        get() = prefs.getInt(K_HTTPS, 2)
        set(v) = prefs.edit { putInt(K_HTTPS, v) }

    /** GPC toggle (LibreWolf enables GPC by default). */
    var doNotTrack: Boolean
        get() = prefs.getBoolean(K_DNT, LibreWolfDefaults.DO_NOT_TRACK)
        set(v) = prefs.edit { putBoolean(K_DNT, v) }

    /**
     * The selected built-in engine, by name.
     *
     * This used to be a position in [LibreWolfDefaults.SEARCH_ENGINES], which
     * meant reordering that list silently repointed everyone's choice: a user
     * on the first entry would find themselves moved to whichever engine took
     * that slot. The name is written to a key of its own and the old index is
     * read through [LibreWolfDefaults.LEGACY_SEARCH_ORDER] so an existing
     * install keeps the engine it already had.
     */
    var searchEngineName: String
        get() = prefs.getString(K_SE_NAME, null)
            ?: if (prefs.contains(K_SE)) {
                LibreWolfDefaults.LEGACY_SEARCH_ORDER.getOrNull(prefs.getInt(K_SE, 0))
            } else {
                null
            }
            ?: LibreWolfDefaults.SEARCH_ENGINES.first().name
        set(v) = prefs.edit { putString(K_SE_NAME, v) }

    /** Name of the selected custom engine, or null when a built-in is used. */
    var customEngine: String?
        get() = prefs.getString(K_CSE, null)
        set(v) = prefs.edit { putString(K_CSE, v) }

    /** Private browsing follows the normal engine unless changed here. */
    var privateUseSameEngine: Boolean
        get() = prefs.getBoolean(K_PSAME, true)
        set(v) = prefs.edit { putBoolean(K_PSAME, v) }

    /** The private-window engine, by name. See [searchEngineName]. */
    var privateSearchEngineName: String
        get() = prefs.getString(K_PSE_NAME, null)
            ?: if (prefs.contains(K_PSE)) {
                LibreWolfDefaults.LEGACY_SEARCH_ORDER.getOrNull(prefs.getInt(K_PSE, 0))
            } else {
                null
            }
            ?: LibreWolfDefaults.SEARCH_ENGINES.first().name
        set(v) = prefs.edit { putString(K_PSE_NAME, v) }

    var privateCustomEngine: String?
        get() = prefs.getString(K_PCSE, null)
        set(v) = prefs.edit { putString(K_PCSE, v) }

    /** User-added engines (name + query URL template containing %s). */
    var customEngines: List<LibreWolfDefaults.SearchEngine>
        get() {
            val out = mutableListOf<LibreWolfDefaults.SearchEngine>()
            val raw = prefs.getString(K_CENG, "[]") ?: "[]"
            try {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(LibreWolfDefaults.SearchEngine(o.optString("n"), o.optString("u")))
                }
            } catch (_e: Exception) {
            }
            return out
        }
        set(v) {
            val arr = org.json.JSONArray()
            for (e in v) {
                arr.put(org.json.JSONObject().put("n", e.name).put("u", e.queryUrl))
            }
            prefs.edit { putString(K_CENG, arr.toString()) }
        }

    var dohProvider: Int
        get() = prefs.getInt(K_DOH, 0)
        set(v) = prefs.edit { putInt(K_DOH, v) }

    /** 0 = off (LibreWolf default), 1 = default protection, 2 = max protection. */
    var dohMode: Int
        get() = prefs.getInt(K_DOHM, 0)
        set(v) = prefs.edit { putInt(K_DOHM, v) }

    /** ETP level: false = standard, true = strict (LibreWolf default). */
    var etpStrict: Boolean
        get() = prefs.getBoolean(K_ETP, true)
        set(v) = prefs.edit { putBoolean(K_ETP, v) }

    /** 0 = off, 1 = malware/unwanted/phishing protection. */
    var safeBrowsingMode: Int
        get() = prefs.getInt(K_SAFE, 1)
        set(v) = prefs.edit { putInt(K_SAFE, v) }

    var remoteDebugging: Boolean
        get() = prefs.getBoolean(K_RD, false)
        set(v) = prefs.edit { putBoolean(K_RD, v) }

    /**
     * Fingerprinting protection: 0 = standard, 1 = strict, 2 = off.
     *
     * Standard is the default because strict reduces entropy the web platform
     * legitimately relies on, which breaks sites that fingerprint for fraud
     * checks rather than for tracking.
     */
    var fingerprintingMode: Int
        get() = prefs.getInt(K_FP, 0)
        set(v) = prefs.edit { putInt(K_FP, v) }

    /** Block social-media trackers on top of the chosen ETP level. */
    var blockSocialTrackers: Boolean
        get() = prefs.getBoolean(K_SOCIAL, false)
        set(v) = prefs.edit { putBoolean(K_SOCIAL, v) }

    /** Keep playing audio when the tab is not in front. */
    var backgroundMedia: Boolean
        get() = prefs.getBoolean(K_BGMEDIA, true)
        set(v) = prefs.edit { putBoolean(K_BGMEDIA, v) }

    /** Block pop-up windows that the user did not ask for. */
    var blockPopups: Boolean
        get() = prefs.getBoolean(K_POPUPS, true)
        set(v) = prefs.edit { putBoolean(K_POPUPS, v) }

    /** Default answer for a site's autoplay request. */
    var autoplayMode: Int
        get() = prefs.getInt(K_AUTOPLAY, 0)
        set(v) = prefs.edit { putInt(K_AUTOPLAY, v) }

    /** Default answer for a site's notification request. */
    var notificationMode: Int
        get() = prefs.getInt(K_NOTIFY, 0)
        set(v) = prefs.edit { putInt(K_NOTIFY, v) }

    /** Floor applied to text the engine renders, in percent. */
    var minimumFontSize: Int
        get() = prefs.getInt(K_FONTSIZE, 0)
        set(v) = prefs.edit { putInt(K_FONTSIZE, v) }

    /** Restrict WebRTC to the default network route to avoid local-IP leaks. */
    var webrtcDefaultRouteOnly: Boolean
        get() = prefs.getBoolean(K_WEBRTC, false)
        set(v) = prefs.edit { putBoolean(K_WEBRTC, v) }

    /** Block every cookie, including first-party ones. */
    var blockAllCookies: Boolean
        get() = prefs.getBoolean(K_COOKIEALL, false)
        set(v) = prefs.edit { putBoolean(K_COOKIEALL, v) }

    /** Show a foreground notification while a download runs. */
    var downloadNotifications: Boolean
        get() = prefs.getBoolean(K_DLNOTIF, true)
        set(v) = prefs.edit { putBoolean(K_DLNOTIF, v) }

    /** Confirm before fetching an installer, a binary or an unencrypted file. */
    var warnRiskyDownloads: Boolean
        get() = prefs.getBoolean(K_DLWARN, true)
        set(v) = prefs.edit { putBoolean(K_DLWARN, v) }

    /** Show the media session notification with transport controls. */
    var mediaNotifications: Boolean
        get() = prefs.getBoolean(K_MEDIANOTIF, true)
        set(v) = prefs.edit { putBoolean(K_MEDIANOTIF, v) }

    /** Allow leaving a playing video to a floating window. */
    var pictureInPicture: Boolean
        get() = prefs.getBoolean(K_PIP, true)
        set(v) = prefs.edit { putBoolean(K_PIP, v) }

    /** Ask before closing a whole group of tabs at once. */
    var confirmCloseTabs: Boolean
        get() = prefs.getBoolean(K_CONFIRMC, false)
        set(v) = prefs.edit { putBoolean(K_CONFIRMC, v) }

    /** Open a new tab in the foreground rather than in the background. */
    var newTabsInForeground: Boolean
        get() = prefs.getBoolean(K_NEWTABFG, false)
        set(v) = prefs.edit { putBoolean(K_NEWTABFG, v) }

    /** Report the browser as a common desktop browser to sites that gate on it. */
    var spoofDesktopUserAgent: Boolean
        get() = prefs.getBoolean(K_SPOOFDESKTOP, false)
        set(v) = prefs.edit { putBoolean(K_SPOOFDESKTOP, v) }

    // What the on-quit clean-up should remove, category by category.
    var sanitizeHistory: Boolean
        get() = prefs.getBoolean(K_SANH, true)
        set(v) = prefs.edit { putBoolean(K_SANH, v) }

    var sanitizeCookies: Boolean
        get() = prefs.getBoolean(K_SANC, true)
        set(v) = prefs.edit { putBoolean(K_SANC, v) }

    var sanitizeSiteData: Boolean
        get() = prefs.getBoolean(K_SANS, true)
        set(v) = prefs.edit { putBoolean(K_SANS, v) }

    var sanitizeCache: Boolean
        get() = prefs.getBoolean(K_SANCA, true)
        set(v) = prefs.edit { putBoolean(K_SANCA, v) }

    var sanitizeDownloads: Boolean
        get() = prefs.getBoolean(K_SAND, false)
        set(v) = prefs.edit { putBoolean(K_SAND, v) }

    var sanitizeBookmarks: Boolean
        get() = prefs.getBoolean(K_SANB, false)
        set(v) = prefs.edit { putBoolean(K_SANB, v) }

    /**
     * Restores every preference to its shipped default.
     *
     * Used by Settings → Advanced → Reset. Called on the store itself rather
     * than by clearing the file so a future key added to this class is included
     * automatically rather than silently left behind.
     */
    fun resetToDefaults() {
        prefs.edit { clear() }
    }

    var openLinksInApps: Boolean
        get() = prefs.getBoolean(K_OL, true)
        set(v) = prefs.edit { putBoolean(K_OL, v) }

    /** Request desktop UA and viewport for sites that need desktop layouts. */
    var desktopMode: Boolean
        get() = prefs.getBoolean(K_DESKTOP, false)
        set(v) = prefs.edit { putBoolean(K_DESKTOP, v) }

    var javascriptEnabled: Boolean
        get() = prefs.getBoolean(K_JS, true)
        set(v) = prefs.edit { putBoolean(K_JS, v) }

    /** Optional folder below the public Downloads directory for new downloads. */
    var downloadFolder: String
        get() = prefs.getString(K_DOWNLOAD_FOLDER, "").orEmpty()
        set(value) {
            val clean = value.replace('\\', '/')
                .split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")
            prefs.edit { putString(K_DOWNLOAD_FOLDER, clean) }
        }

    /** SAF tree URI for [downloadFolder], set by the system Files picker. */
    var downloadFolderUri: String
        get() = prefs.getString(K_DOWNLOAD_FOLDER_URI, "").orEmpty()
        set(value) = prefs.edit { putString(K_DOWNLOAD_FOLDER_URI, value) }

    /** 0 = follow system, 1 = light, 2 = dark. */
    var themeMode: Int
        get() = prefs.getInt(K_THEME, 2)
        set(v) = prefs.edit { putInt(K_THEME, v) }

    var homepage: String
        get() = prefs.getString(K_HOME, MainActivity.HOME_START) ?: MainActivity.HOME_START
        set(v) = prefs.edit { putString(K_HOME, v) }

    // ---- Search (address bar) ----

    /** Master toggle for history/bookmark suggestions. LibreWolf default: off. */
    var searchSuggestions: Boolean
        get() = prefs.getBoolean(K_SUG, false)
        set(v) = prefs.edit { putBoolean(K_SUG, v) }

    /** Allow suggestions while a private tab is active (LibreWolf default: off). */
    var searchSuggestionsPrivate: Boolean
        get() = prefs.getBoolean(K_SUG_P, false)
        set(v) = prefs.edit { putBoolean(K_SUG_P, v) }

    var showRecentSearches: Boolean
        get() = prefs.getBoolean(K_RECENT, true)
        set(v) = prefs.edit { putBoolean(K_RECENT, v) }

    var searchHistory: Boolean
        get() = prefs.getBoolean(K_HIST, true)
        set(v) = prefs.edit { putBoolean(K_HIST, v) }

    var searchBookmarks: Boolean
        get() = prefs.getBoolean(K_BMARK, true)
        set(v) = prefs.edit { putBoolean(K_BMARK, v) }

    var showClipboardSuggestions: Boolean
        get() = prefs.getBoolean(K_CLIP, true)
        set(v) = prefs.edit { putBoolean(K_CLIP, v) }

    var voiceSearch: Boolean
        get() = prefs.getBoolean(K_VOICE, false)
        set(v) = prefs.edit { putBoolean(K_VOICE, v) }

    var autocompleteUrls: Boolean
        get() = prefs.getBoolean(K_AURL, true)
        set(v) = prefs.edit { putBoolean(K_AURL, v) }

    /** Resolves the engine to use, honouring normal/private selection. */
    fun engineFor(privateTab: Boolean): LibreWolfDefaults.SearchEngine {
        val built = LibreWolfDefaults.SEARCH_ENGINES
        val customs = customEngines
        if (privateTab && !privateUseSameEngine) {
            privateCustomEngine?.let { name ->
                customs.find { it.name == name }?.let { return it }
            }
            return built.find { it.name == privateSearchEngineName } ?: built[0]
        }
        customEngine?.let { name ->
            customs.find { it.name == name }?.let { return it }
        }
        return built.find { it.name == searchEngineName } ?: built[0]
    }

    // ---- Tabs ----

    /** Columns in the tab tray: 1 = list, 2 = grid. */
    var tabViewColumns: Int
        get() = prefs.getInt(K_TVC, 2)
        set(v) = prefs.edit { putInt(K_TVC, v) }

    /** Auto-close tabs after N days: 0 never, 1, 7, 30. */
    var closeTabsAfterDays: Int
        get() = prefs.getInt(K_CTD, 0)
        set(v) = prefs.edit { putInt(K_CTD, v) }

    var moveOldTabsToInactive: Boolean
        get() = prefs.getBoolean(K_INACTIVE, false)
        set(v) = prefs.edit { putBoolean(K_INACTIVE, v) }

    var privacyReport: Boolean
        get() = prefs.getBoolean(K_PRIVREP, true)
        set(v) = prefs.edit { putBoolean(K_PRIVREP, v) }

    var tabGroups: Boolean
        get() = prefs.getBoolean(K_TGROUPS, true)
        set(v) = prefs.edit { putBoolean(K_TGROUPS, v) }

    /** Running total of blocked trackers for the privacy report. */
    var cumulativeTrackers: Int
        get() = prefs.getInt(K_CUMTRACK, 0)
        set(v) = prefs.edit { putInt(K_CUMTRACK, v) }

    /**
     * Running total of Safe Browsing blocks. Kept apart from the tracker count
     * because a malware or phishing verdict is a threat, not a tracker.
     */
    var cumulativeThreats: Int
        get() = prefs.getInt(K_CUMTHREAT, 0)
        set(v) = prefs.edit { putInt(K_CUMTHREAT, v) }

    companion object {
        private const val PREFS = "librewolf_privacy"
        private const val K_3P = "block_3p_cookies"
        private const val K_SAN = "sanitize_on_shutdown"
        private const val K_HTTPS = "https_mode"
        private const val K_DNT = "dnt"
        /** Legacy index of the built-in engine. Read only; see [searchEngineName]. */
        private const val K_SE = "search_engine"
        private const val K_SE_NAME = "search_engine_name"
        private const val K_CSE = "custom_engine"
        private const val K_PSAME = "private_same_engine"

        /** Legacy index of the private built-in engine. Read only. */
        private const val K_PSE = "private_search_engine"
        private const val K_PSE_NAME = "private_search_engine_name"
        private const val K_PCSE = "private_custom_engine"
        private const val K_CENG = "custom_engines"
        private const val K_DOH = "doh_provider"
        private const val K_DOHM = "doh_mode"
        private const val K_ETP = "etp_strict"
        private const val K_SAFE = "safe_browsing_mode"
        private const val K_RD = "remote_debugging"
        private const val K_OL = "open_links_in_apps"
        private const val K_DESKTOP = "desktop_mode"
        private const val K_JS = "javascript_enabled"
        private const val K_DOWNLOAD_FOLDER = "download_folder"
    private const val K_DOWNLOAD_FOLDER_URI = "download_folder_uri"
        private const val K_THEME = "theme_mode"
        private const val K_HOME = "homepage"
        private const val K_SUG = "search_suggestions"
        private const val K_SUG_P = "search_suggestions_private"
        private const val K_RECENT = "show_recent_searches"
        private const val K_HIST = "search_history"
        private const val K_BMARK = "search_bookmarks"
        private const val K_CLIP = "clipboard_suggestions"
        private const val K_VOICE = "voice_search"
        private const val K_AURL = "autocomplete_urls"
        private const val K_TVC = "tab_view_columns"
        private const val K_CTD = "close_tabs_days"
        private const val K_INACTIVE = "move_old_inactive"
        private const val K_PRIVREP = "privacy_report"
        private const val K_TGROUPS = "tab_groups"
        private const val K_CUMTRACK = "cumulative_trackers"
        private const val K_CUMTHREAT = "cumulative_threats"
        private const val K_FP = "fingerprinting_mode"
        private const val K_SOCIAL = "block_social_trackers"
        private const val K_BGMEDIA = "background_media"
        private const val K_POPUPS = "block_popups"
        private const val K_AUTOPLAY = "autoplay_mode"
        private const val K_NOTIFY = "notification_mode"
        private const val K_FONTSIZE = "minimum_font_size"
        private const val K_WEBRTC = "webrtc_default_route"
        private const val K_COOKIEALL = "block_all_cookies"
        private const val K_DLNOTIF = "download_notifications"
        private const val K_DLWARN = "warn_risky_downloads"
        private const val K_MEDIANOTIF = "media_notifications"
        private const val K_PIP = "picture_in_picture"
        private const val K_CONFIRMC = "confirm_close_tabs"
        private const val K_NEWTABFG = "new_tabs_foreground"
        private const val K_SPOOFDESKTOP = "spoof_desktop_ua"
        private const val K_SANH = "sanitize_history"
        private const val K_SANC = "sanitize_cookies"
        private const val K_SANS = "sanitize_site_data"
        private const val K_SANCA = "sanitize_cache"
        private const val K_SAND = "sanitize_downloads"
        private const val K_SANB = "sanitize_bookmarks"

        private const val K_ONBOARDED = "onboarding_complete"
        private const val K_NOTIF_ASKED = "notification_permission_asked"
    }

    /**
     * False until the first-run walkthrough has been seen or skipped. Kept here
     * rather than in the activity so the decision survives a process death during
     * onboarding rather than replaying on every cold start.
     */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(K_ONBOARDED, false)
        set(v) = prefs.edit { putBoolean(K_ONBOARDED, v) }

    /**
     * True once the app has put the Android notification permission in front of
     * the user. Without this, every site that asks for notifications launches
     * another system dialog, which reads as the browser pestering the user.
     * Cleared only when the user deliberately asks for it again from Settings.
     */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(K_NOTIF_ASKED, false)
        set(v) = prefs.edit { putBoolean(K_NOTIF_ASKED, v) }
}
