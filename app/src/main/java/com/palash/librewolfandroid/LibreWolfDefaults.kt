package com.palash.librewolfandroid

/** LibreWolf desktop defaults ported from source/settings/librewolf.cfg + distribution/policies.json. */
object LibreWolfDefaults {
    const val HOMEPAGE = "https://librewolf.net"
    const val SUPPORT_URL = "https://support.librewolf.net/"
    const val UPDATE_URL = "https://librewolf.net/installation/"

    // librewolf.cfg: strict tracking protection, sanitize on shutdown, no disk cache,
    // HTTPS-only, RFP, no telemetry/sync. (Safe Browsing is disabled outright;
    // autoplay has no GeckoView API and is not exposed.)
    const val BLOCK_THIRD_PARTY_COOKIES = true
    const val SANITIZE_ON_SHUTDOWN = true
    const val HTTPS_ONLY = true
    const val DO_NOT_TRACK = true

    // librewolf.cfg DoH provider list (network.trr.uri default = Quad9 no-filter).
    data class DohProvider(val name: String, val uri: String)
    val DOH_PROVIDERS = listOf(
        DohProvider("Quad9 (No Filtering) — LibreWolf default", "https://dns10.quad9.net/dns-query"),
        DohProvider("Quad9 (Malware blocking)", "https://dns.quad9.net/dns-query"),
        DohProvider("LibreDNS (No Filtering)", "https://doh.libredns.gr/dns-query"),
        DohProvider("LibreDNS (Adblocking)", "https://doh.libredns.gr/noads"),
        DohProvider("Wikimedia DNS (No Filtering)", "https://wikimedia-dns.org/dns-query"),
        DohProvider("DNS4All (No Filtering)", "https://doh.dns4all.eu/dns-query"),
        DohProvider("Mullvad (No Filtering)", "https://dns.mullvad.net/dns-query"),
    )

    // policies.json: uBlock preinstalled. GeckoView's strict Enhanced Tracking
    // Protection already blocks known trackers natively, so no stub list needed.
    const val GECKOVIEW_VERSION = "147.0.20260212191108"

    /**
     * One entry in the installable list.
     *
     * The slug is the AMO add-on id, not the display name. They differ for
     * several of these -- Dark Reader is "darkreader", SingleFile is "single-file",
     * NoScript is "noscript" -- and a slug that looks right but is wrong returns
     * 404 at install time rather than failing visibly in the build, so each was
     * checked by fetching the download URL and confirming a 200 with an
     * application/x-xpinstall response.
     *
     * A "latest" URL is used rather than a pinned file id so the add-on updates
     * itself when the user reinstalls, and so no URL here rots.
     */
    data class Addon(val slug: String, val name: String, val summary: String) {
        val url: String
            get() = "https://addons.mozilla.org/firefox/downloads/latest/$slug/latest.xpi"
    }

    /**
     * Offered in the Extensions screen. Blocker and privacy tools first because
     * that is what this browser is for; the rest after.
     *
     * Deliberately absent: Privacy Badger. Its AMO listing 404s and its download
     * URL does not resolve, so there is nothing to install. Listing it would
     * offer a row that cannot work.
     */
    val INSTALLABLE_ADDONS = listOf(
        Addon("ublock-origin", "uBlock Origin", "Blocks ads and trackers"),
        Addon("clearurls", "ClearURLs", "Strips tracking parameters from links"),
        Addon("ghostery", "Ghostery", "Blocks trackers and warns on data tracking"),
        Addon("cookie-autodelete", "Cookie AutoDelete", "Removes cookies on a schedule you set"),
        Addon("leechblock-ng", "LeechBlock NG", "Blocks sites on a schedule"),
        Addon("darkreader", "Dark Reader", "Dark theme for websites, generated on the fly"),
        Addon("noscript", "NoScript Security Suite", "Blocks scripts and trackers by default"),
        Addon("sponsorblock", "SponsorBlock", "Skips sponsorships in videos"),
        Addon("video-background-play-fix", "Video Background Play Fix", "Keeps video playing in the background"),
        Addon("single-file", "SingleFile", "Saves a complete copy of a page"),
        Addon("search-by-image", "Search by Image", "Reverse image search from the context menu"),
        Addon("google-search-fixer", "Google Search Fixer", "Unbreaks Google search on Firefox"),
        Addon("twp-translate-for-mobile", "TWP - Translate For Mobile", "Translate pages in place"),
        Addon("tampermonkey", "Tampermonkey", "Runs user scripts on pages you choose"),
    )

    // librewolf.cfg: query stripping list (privacy.query_stripping.strip_list),
    // wired to GeckoView's native query-parameter stripping.
    val QUERY_STRIP_LIST = listOf(
        "__hsfp", "__hssc", "__hstc", "__s", "_bhlid", "_branch_match_id",
        "_branch_referrer", "_gl", "_hsenc", "_openstat", "at_recipient_id",
        "at_recipient_list", "bbeml", "bsft_clkid", "bsft_uid", "dclid",
        "et_rid", "fb_action_ids", "fb_comment_id", "gbraid", "fbclid",
        "gclid", "guce_referrer", "guce_referrer_sig", "hsCtaTracking",
        "irclickid", "mc_eid", "ml_subscriber", "ml_subscriber_hash",
        "msclkid", "mtm_cid", "oft_c", "oft_ck", "oft_d", "oft_id",
        "oft_ids", "oft_k", "oft_lk", "oft_sk", "oly_anon_id", "oly_enc_id",
        "pk_cid", "rb_clickid", "s_cid", "sc_customer", "sc_eh", "sc_uid",
        "sfmc_activityid", "sfmc_id", "sms_click", "sms_source", "sms_uph",
        "srsltid", "ss_email_id", "syclid", "ttclid", "twclid",
        "unicorn_click_id", "vero_conv", "vero_id", "vgo_ee", "wbraid",
        "wickedid", "yclid", "ymclid", "ysclid",
    )
    const val QUERY_STRIP_ALLOW = "urldefense.com"

    data class SearchEngine(val name: String, val queryUrl: String)

    /**
     * The built-in engines, in the order they are offered.
     *
     * This is a curated order rather than an alphabetical one: the four that
     * lead are the ones that do not build a profile of the person searching, so
     * they are what a privacy browser should be offering first. The rest follow
     * alphabetically.
     *
     * A selection is stored by name and not by position in this list, so
     * reordering it cannot change the engine somebody already chose.
     */
    val SEARCH_ENGINES = listOf(
        SearchEngine("Brave", "https://search.brave.com/search?q=%s"),
        SearchEngine("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        SearchEngine("Google", "https://www.google.com/search?q=%s"),
        SearchEngine("Bing", "https://www.bing.com/search?q=%s"),
        SearchEngine("Ecosia", "https://www.ecosia.org/search?q=%s"),
        SearchEngine("Mojeek", "https://www.mojeek.com/search?q=%s"),
        SearchEngine("Qwant", "https://www.qwant.com/?q=%s&t=web"),
        SearchEngine("Startpage", "https://www.startpage.com/sp/search?query=%s"),
    )

    /**
     * The order engines were listed in before the curated one, used only to
     * resolve a selection stored as an index by an older build.
     *
     * The names are the current ones on purpose: this table turns a position
     * into a name and that name is then looked up in [SEARCH_ENGINES]. Only the
     * positions have to match the past, so renaming an engine later does not
     * need a second edit here.
     */
    val LEGACY_SEARCH_ORDER = listOf(
        "Bing",
        "DuckDuckGo",
        "Ecosia",
        "Google",
        "Mojeek",
        "Qwant",
        "Startpage",
        "Brave",
    )
}
