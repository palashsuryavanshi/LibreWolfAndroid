package com.palash.librewolfandroid

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.webkit.URLUtil
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import java.net.URLEncoder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autofill
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * Browser home: official LibreWolf logo, private-tab button, tracker pill, bottom
 * toolbar (home | address | tab count | menu), tab tray, menu sheet.
 * Engine settings mirror source/settings/librewolf.cfg.
 */
class MainActivity : AppCompatActivity() {

    private data class Tab(
        val id: Long,
        // Replaced when a tab is suspended under memory pressure and revived when
        // it is selected again, so `session` cannot be a val.
        var session: GeckoSession,
        var title: String = "",
        var url: String = "",
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
        var isSecure: Boolean = false,
        var isError: Boolean = false,
        var isLoading: Boolean = false,
        var trackers: Int = 0,
        var threats: Int = 0,
        var lastActiveAt: Long = System.currentTimeMillis(),
        val isPrivate: Boolean = false,
        var isHome: Boolean = true,
        var suspended: Boolean = false,
        // Restored tabs carry their URL as metadata only: the session starts
        // at about:blank and the page loads on first selection. Loading every
        // restored tab at startup meant N sessions fetching at once in front
        // of the first paint.
        var needsLoad: Boolean = false,
        // GeckoView opens popup sessions itself after onNewSession returns. Select
        // the popup only after that asynchronous open has completed.
        var selectWhenOpened: Boolean = false,
    ) {
        fun domain(): String = try {
            android.net.Uri.parse(url).host?.removePrefix("www.") ?: url
        } catch (_e: Exception) {
            url
        }
    }

    companion object {
        const val HOME_START = "lw:start"
        const val ACTION_PRIVATE_TAB = "com.palash.librewolfandroid.PRIVATE_TAB"
        const val ACTION_ABOUT = "com.palash.librewolfandroid.ABOUT"
        const val ACTION_OPEN_NOTIFICATION = "com.palash.librewolfandroid.OPEN_NOTIFICATION"
        const val ACTION_DATA_CLEARED = "com.palash.librewolfandroid.DATA_CLEARED"
        const val EXTRA_NOTIFICATION_URL = "notification_url"
        private const val EXTRA_WEB_APP = "com.palash.librewolfandroid.WEB_APP"

        /** How long a paused download waits for Gecko to hand back a fresh body. */
        private const val REFRESH_TIMEOUT_MS = 30_000L

        internal const val AUTOFILL_TAG = "LWAutofill"

        /** Schemes Android owns; anything else is a file the browser keeps. */
        private val HANDOFF_SCHEMES = setOf(
            "mailto", "tel", "sms", "smsto", "mms", "geo", "market", "intent",
            "news", "sip", "sips", "webcal", "im", "irc", "ircs", "xmpp",
        )

        /**
         * The agent string sent when the user asks to be reported as a common
         * desktop browser. It matches what Firefox itself sends for a manual
         * override, so a site that gates on the string behaves the same way it
         * does there.
         */
        private const val MICROMIUM_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"

        // One GeckoRuntime per process (GeckoView requirement).
        internal var runtime: GeckoRuntime? = null

        fun runtimeRef(): GeckoRuntime? = runtime

        /** Best-effort engine wipe usable from Settings. */
        fun eraseEngineData(flags: Long = StorageController.ClearFlags.ALL) {
            try {
                runtime?.storageController?.clearData(flags)
            } catch (_: Exception) {
            }
        }

        /**
         * The on-quit clean-up, category by category.
         *
         * Downloads are not an engine category, so they are handled by the
         * caller through [DownloadStore]; everything else is a clear flag.
         */
        fun eraseEngineDataOnQuit(store: PrivacyStore) {
            var flags = 0L
            if (store.sanitizeCookies) flags = flags or StorageController.ClearFlags.COOKIES
            if (store.sanitizeCache) flags = flags or StorageController.ClearFlags.ALL_CACHES
            if (store.sanitizeSiteData) flags = flags or StorageController.ClearFlags.SITE_DATA
            if (flags != 0L) eraseEngineData(flags)
            if (store.sanitizeDownloads) {
                val context = storeContext ?: return
                runCatching {
                    DownloadStore(context).all().forEach { DownloadEngine.remove(it.id) }
                    DownloadStore(context).clearAll()
                }
            }
        }

        /**
         * A context for the static clean-up helpers.
         *
         * The store holds no context of its own, so one is attached here for the
         * lifetime of the process and reused; `Context.getApplicationContext`
         * keeps it from leaking an activity.
         */
        private var storeContext: Context? = null

        fun attachContext(context: Context) {
            storeContext = context.applicationContext
        }
    }

    private val tabs = mutableListOf<Tab>()
    private var nextId = 1L
    private var activeId = -1L
    private lateinit var store: PrivacyStore
    private lateinit var historyStore: HistoryStore
    private lateinit var bookmarkStore: BookmarkStore
    private lateinit var sessionStore: BrowserSessionStore
    private lateinit var sitePermissionStore: SitePermissionStore
    private lateinit var loginStore: LoginStore
    private lateinit var mediaController: BrowserMediaController

    /** The standing "this tab is private" reminder. */
    private lateinit var privateIndicator: PrivateBrowsingNotifier

    private lateinit var geckoView: GeckoView
    private lateinit var browserControls: View
    private lateinit var homeOverlay: LinearLayout
    private var trackersText: TextView? = null
    private lateinit var trayTrackersText: String
    private lateinit var progress: ProgressBar
    private lateinit var addressText: EditText
    private lateinit var tabsCount: TextView
    private var suggestionsAdapter: SuggestionsAdapter? = null
    private var suggestionsBox: android.view.ViewGroup? = null
    /**
     * Suggestion inputs, cached. updateSuggestions used to re-parse the whole
     * history and bookmark JSON on every keystroke -- three full parses per
     * character on the main thread. The lists only change when a page loads
     * (this activity) or when History/Bookmarks screens edit them (while this
     * activity is stopped), so a cache invalidated on resume and on local
     * write is always fresh and never parsed twice for the same content.
     */
    private var suggestionHistory: List<HistoryEntry>? = null
    private var suggestionBookmarks: List<Bookmark>? = null
    private val suggestionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var suggestionPending: Runnable? = null
    private var menuSheet: MenuSheet? = null
    private var tabsSheet: TabsSheet? = null
    private var fullscreenSession: GeckoSession? = null
    private var webAppManifest: org.json.JSONObject? = null
    private var webAppUrl: String? = null
    private var webAppIconUrl: String? = null
    private var immersiveMode = false
    // True only between the Quit confirmation and process end. See askQuit
    // and onDestroy: destruction alone must not wipe user data.
    private var quitting = false

    /** Origin the browser was launched for from a pinned web-app shortcut. */
    private var webAppOrigin: String? = null
    private var chromeRevealed = false
    private var tapDownAt = 0L
    private var tapDownX = 0f
    private var tapDownY = 0f

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrEmpty()) navigate(spoken)
        }

    private lateinit var promptDelegate: BrowserPromptDelegate
    private lateinit var permissionDelegate: BrowserPermissionDelegate
    private val permissionCompletions = mutableListOf<(Boolean) -> Unit>()

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (::promptDelegate.isInitialized) {
                promptDelegate.onFilePickerResult(result.resultCode, result.data)
            }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (::promptDelegate.isInitialized) promptDelegate.onCameraResult(success)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            val completions = permissionCompletions.toList()
            permissionCompletions.clear()
            completions.forEach { it(granted) }
        }

    /** Address-bar suggestions driven by Settings > Search. */
    private fun updateSuggestions(raw: String) {
        // Keystrokes arrive faster than a parse-and-render cycle matters: wait
        // for a pause before doing the work, and drop whatever is still queued
        // when the box hides (navigation, focus loss) so a stale run cannot
        // pop the box back open after it was dismissed.
        suggestionPending?.let { suggestionHandler.removeCallbacks(it) }
        val job = Runnable { updateSuggestionsNow() }
        suggestionPending = job
        suggestionHandler.postDelayed(job, 120)
    }

    private fun updateSuggestionsNow() {
        suggestionPending = null
        val raw = if (addressText.hasFocus()) addressText.text.toString() else return
        val box = suggestionsBox ?: findViewById<android.view.ViewGroup>(R.id.suggestions_box)
        suggestionsBox = box
        val text = raw.trim()
        val private = activeTab()?.isPrivate == true
        val allowPrivate = store.searchSuggestionsPrivate
        val suggestionsOn = store.searchSuggestions && (!private || allowPrivate)
        val items = mutableListOf<Suggestion>()

        if (text.isNotEmpty()) {
            if (suggestionsOn) {
                // One parse per run, not three: history is read once and shared
                // by the matches below and the recent-search row.
                val history = if (store.searchHistory || (store.showRecentSearches && !private)) {
                    suggestionHistory ?: historyStore.all().also { suggestionHistory = it }
                } else {
                    emptyList()
                }
                if (store.searchHistory) {
                    history
                        .filter { it.title.contains(text, true) || it.url.contains(text, true) }
                        .take(4)
                        .forEach { e ->
                            items.add(
                                Suggestion(e.title, e.url, badgeLetter(e.title, e.url)) {
                                    navigate(e.url)
                                },
                            )
                        }
                }
                if (store.searchBookmarks) {
                    val bookmarks = suggestionBookmarks
                        ?: bookmarkStore.all().also { suggestionBookmarks = it }
                    bookmarks
                        .filter { it.title.contains(text, true) || it.url.contains(text, true) }
                        .take(3)
                        .forEach { b ->
                            items.add(Suggestion(b.title, b.url, badgeLetter(b.title, b.url)) {
                                navigate(b.url)
                            })
                        }
                }
                if (store.showRecentSearches && !private) {
                    history.firstOrNull { it.url != text }?.let { e ->
                        if (items.none { it.sub == e.url }) {
                            items.add(
                                Suggestion(
                                    getString(R.string.sug_recent),
                                    e.title.ifEmpty { e.url },
                                    "R",
                                ) { navigate(e.url) },
                            )
                        }
                    }
                }
            }
            if (store.showClipboardSuggestions) {
                val clip = readClipboard()
                if (!clip.isNullOrBlank() && clip != text && (items.none { it.sub == clip })) {
                    items.add(
                        Suggestion(
                            getString(R.string.sug_clipboard),
                            clip,
                            "C",
                        ) {
                            addressText.setText(clip)
                            addressText.setSelection(clip.length)
                        },
                    )
                }
            }
            if (store.autocompleteUrls) {
                if (text.contains(".") && !text.contains(" ")) {
                    items.add(
                        Suggestion(
                            getString(R.string.sug_go, "https://$text"),
                            text,
                            ">",
                        ) { navigate(text) },
                    )
                }
                val engine = store.engineFor(private)
                items.add(
                    Suggestion(
                        getString(R.string.sug_search, engine.name),
                        text,
                        "S",
                    ) { navigateSearch(text) },
                )
            }
        }
        if (items.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        suggestionsAdapter?.update(items)
        box.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        suggestionPending?.let { suggestionHandler.removeCallbacks(it) }
        suggestionPending = null
        suggestionsBox?.visibility = View.GONE
    }

    private fun readClipboard(): String? = try {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_e: Exception) {
        null
    }

    private fun startVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_e: Exception) {
            android.widget.Toast.makeText(this, getString(R.string.not_available), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    init {
        trayTrackersText = ""
    }

    private fun tabFor(session: GeckoSession): Tab? = tabs.find { it.session === session }
    private fun activeTab(): Tab? = tabs.find { it.id == activeId }
    internal fun activeTabId(): Long = activeId
    internal fun tabIdForSession(session: GeckoSession): Long? = tabFor(session)?.id

    // ---- shared session delegates ----

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> {
            val uri = request.uri
            val scheme = runCatching { Uri.parse(uri).scheme?.lowercase() }.getOrNull()
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" ||
                scheme == "file" || scheme == "blob" || scheme == "content" || scheme == "filesystem"
            ) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
            // Gecko cannot load Android protocols itself. Route only explicit
            // top-level requests; subframes stay inside the engine below.
            val isIntentUri = uri.startsWith("intent:", ignoreCase = true)
            if (request.hasUserGesture || (store.openLinksInApps && !isIntentUri)) {
                openExternalUri(uri)
            }
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            val tab = tabFor(session) ?: return
            if (url != null) {
                val oldHost = runCatching { Uri.parse(webAppUrl.orEmpty()).host }.getOrNull()
                val newHost = runCatching { Uri.parse(url).host }.getOrNull()
                if (oldHost != null && newHost != null && oldHost != newHost) {
                    webAppManifest = null
                    webAppUrl = null
                    webAppIconUrl = null
                }
                // A fresh session reports an initial about:blank location commit
                // when it opens, carrying no navigation. Applied blindly it wipes
                // a URL the tab already holds -- restored tabs lost their URLs to
                // this within milliseconds of creation, and the next save then
                // dropped them from restore entirely. The commit is only ever
                // informative on a tab that has nothing yet.
                if (url != "about:blank" || (tab.url.isBlank() || tab.url == "about:blank")) {
                    tab.url = url
                }
            }
            if (tab.id == activeId) {
                updateAddress()
                applyWebAppDisplayMode()
            }
            tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tabFor(session)?.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tabFor(session)?.canGoForward = canGoForward
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError,
        ): GeckoResult<String> {
            val tab = tabFor(session)
            if (tab != null) {
                tab.isError = true
                // The error document Gecko loads next reports its own progress
                // callbacks, but if it never does the spinner would stick on
                // the failed state. Clear it here; a real load re-sets it.
                tab.isLoading = false
            }
            if (tab?.id == activeId) {
                progress.visibility = View.GONE
                homeOverlay.visibility = View.GONE
                geckoView.visibility = View.VISIBLE
            }
            tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
            return GeckoResult.fromValue(BrowserErrorPage.dataUri(uri, error))
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // GeckoView requires onNewSession to return a newly-created, *unopened*
            // session. It opens the session and loads `uri` itself after this result
            // completes. Calling newTab()/open()/loadUri() here violates that contract
            // and crashes the app on window.open(), target=_blank, DRM handshakes,
            // OAuth, payment, and other sites that open a second browsing context.
            return try {
                val opener = tabFor(session)
                // "Block pop-up windows" means a window the site opened on its own
                // is refused. Returning null is how GeckoView is told the window
                // was blocked. A link the user tapped is a top-level navigation,
                // not a pop-up, so it is unaffected.
                if (store.blockPopups) {
                    blockedPopupCount++
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.popup_blocked),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    null
                } else {
                    GeckoResult.fromValue(createUnopenedPopup(opener?.isPrivate == true))
                }
            } catch (_: Exception) {
                // Returning null tells Gecko the popup was blocked. It is safer than
                // allowing an embedder-side exception to terminate the browser process.
                null
            }
        }
    }

    /** Pop-ups refused because the setting blocks them, for the privacy report. */
    private var blockedPopupCount: Int = 0

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            selectPendingPopup(session)
            val tab = tabFor(session) ?: return
            if (isBlank(url)) {
                // GeckoView sessions start at about:blank; that is our start
                // page, not a navigation. Popup sessions are already marked
                // non-home because Gecko owns their initial load.
                if (tab.isHome) showHomeState(tab)
                return
            }
            tab.url = url
            tab.isError = false
            tab.isLoading = true
            tab.isHome = false
            if (!tab.isPrivate) {
                FaviconCache.load(url) {
                    if (!isFinishing && !isDestroyed && tabFor(session)?.url == url) {
                        tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
                    }
                }
            }
            if (tab.id == activeId) {
                homeOverlay.visibility = View.GONE
                geckoView.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                updateAddress()
            }
            tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            val tab = tabFor(session) ?: return
            tab.isLoading = false
            if (isBlank(tab.url) || tab.url.startsWith("data:") || tab.isError) return
            if (success && !tab.isPrivate) {
                historyStore.add(tab.title, tab.url)
                suggestionHistory = null
                }
            // The URL/title just committed are what restore rebuilds from.
            // Saving on pause alone leaves every navigation since the last
            // backgrounding out of the store, so a sudden death (crash,
            // force-stop) would lose them. Serializing a few dozen small
            // records per page load is negligible next to the page itself.
            if (success) saveTabState()
            if (tab.id == activeId) {
                progress.visibility = View.GONE
                updateAddress()
            }
            tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
        }

        override fun onProgressChange(session: GeckoSession, newProgress: Int) {
            val tab = tabFor(session) ?: return
            tab.isLoading = newProgress in 1..99
            if (tab.id == activeId) {
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                progress.progress = newProgress
            }
            tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
        ) {
            val tab = tabFor(session) ?: return
            tab.isSecure = securityInfo.isSecure
            if (tab.id == activeId) updateAddress()
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            val tab = tabFor(session) ?: return
            tab.title = title.orEmpty()
            if (tab.id == activeId) updateAddress()
            tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
        }

        override fun onWebAppManifest(session: GeckoSession, manifest: org.json.JSONObject) {
            val tab = tabFor(session) ?: return
            webAppManifest = manifest
            webAppUrl = tab.url
            webAppIconUrl = webAppIcon(manifest, tab.url)
            webAppIconUrl?.let { FaviconCache.prefetchImage(it) }
            applyWebAppDisplayMode()
        }

        override fun onContextMenu(
            session: GeckoSession,
            x: Int,
            y: Int,
            contextElement: GeckoSession.ContentDelegate.ContextElement,
        ) {
            showContextMenu(session, contextElement)
        }

        override fun onCrash(session: GeckoSession) {
            showContentProcessFailure(session, R.string.page_crashed)
        }

        override fun onKill(session: GeckoSession) {
            showContentProcessFailure(session, R.string.page_stopped)
        }

        override fun onFirstComposite(session: GeckoSession) {
            // This is the earliest reliable point at which GeckoView has finished
            // opening a session returned from onNewSession.
            selectPendingPopup(session)
        }

        override fun onCloseRequest(session: GeckoSession) {
            // Handle window.close() and web-content close requests without leaving
            // stale sessions in the tab tray.
            tabFor(session)?.let { closeTab(it.id) }
        }

        override fun onFullScreen(session: GeckoSession, isFullScreen: Boolean) {
            if (tabFor(session)?.id != activeId) return
            setBrowserFullscreen(session, isFullScreen)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            if (isInternalOnlyUri(response.uri)) {
                // javascript:/data: are executed inside the page, never handed to
                // Android. Letting one reach the system leaks the script body into
                // the share sheet and any other app that claims VIEW intents.
                return
            }
            val scheme = response.uri.trimStart().lowercase().substringBefore(':')
            // Only a scheme Android owns is handed off. A *file* the engine chose
            // to delegate is still a download: sending its URL to another app
            // re-fetches it without this session's cookies, bypasses the
            // download engine and its safety check, and on most devices ends in a
            // "no app found" toast with nothing saved.
            if (response.requestExternalApp && scheme in HANDOFF_SCHEMES) {
                openExternalUri(response.uri)
                return
            }
            session.getUserAgent().accept { userAgent ->
                // Gecko has already fetched this response with the session
                // cookie attached, so its body is the only copy we can get for
                // a file behind a sign-in. Re-requesting the URL here would
                // return the login page instead.
                enqueueDownload(
                    response.uri,
                    response.headers,
                    userAgent,
                    response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value,
                    body = response.body,
                    contentLength = response.headers.entries
                        .firstOrNull { it.key.equals("Content-Length", true) }
                        ?.value?.toLongOrNull() ?: -1L,
                )
            }
        }
    }

    /** Schemes the page executes itself; never forward these to Android. */
    private fun isInternalOnlyUri(uri: String): Boolean {
        val lower = uri.trimStart().lowercase()
        return lower.startsWith("javascript:") || lower.startsWith("data:") ||
            lower.startsWith("blob:") || lower.startsWith("about:")
    }

    private fun selectPendingPopup(session: GeckoSession) {
        val tab = tabFor(session) ?: return
        if (!tab.selectWhenOpened || !session.isOpen || isFinishing || isDestroyed) return
        tab.selectWhenOpened = false
        selectTab(tab.id)
    }

    /**
     * Fills a login form from the encrypted local vault when the user focuses a
     * login field. GeckoView 147 routes field focus through
     * `Autofill.Delegate`; without this, the vault could be written but never
     * read back into a page.
     */
    /**
     * Offers the saved login for this site so the user can copy it into the form.
     *
     * The value is deliberately not injected into the page. GeckoView 147
     * installs its own `Autofill.Delegate` when the view attaches (so
     * `session.autofillDelegate` cannot be used), and it exposes no scripting
     * API, so a `javascript:` fill both failed to run and leaked the script to
     * the system share sheet. Copying is explicit, works everywhere, and never
     * hands page content to another app.
     */
    private fun fillLoginFromVault(tab: Tab) {
        val url = tab.url
        if (url.isBlank() || tab.isHome) return
        val login = loginStore.forOrigin(url).firstOrNull()
        if (login == null) {
            Toast.makeText(this, R.string.no_saved_login, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        AlertDialog.Builder(this)
            .setTitle(R.string.fill_login)
            .setItems(
                arrayOf(getString(R.string.copy_username), getString(R.string.copy_password)),
            ) { _, which ->
                val value = if (which == 0) login.username else login.password
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(getString(R.string.app_name), value),
                )
                Toast.makeText(
                    this,
                    if (which == 0) R.string.username_copied else R.string.password_copied,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val autofillDelegate = object : Autofill.Delegate {
        override fun onNodeFocus(
            session: GeckoSession,
            node: Autofill.Node,
            nodeData: Autofill.NodeData,
        ) {
            runCatching { autofillFromVault(session, node) }
        }
    }

    private fun autofillFromVault(session: GeckoSession, focused: Autofill.Node) {
        val autofillSession = session.autofillSession
        if (autofillSession == null) {
            android.util.Log.d(AUTOFILL_TAG, "no autofill session")
            return
        }
        val root = autofillSession.root
        if (root == null) {
            android.util.Log.d(AUTOFILL_TAG, "no autofill root")
            return
        }
        val nodes = collectNodes(root)
        android.util.Log.d(
            AUTOFILL_TAG,
            "focus hint=${focused.getHint()} attrs=${focused.getAttributes()}",
        )
        nodes.forEach {
            android.util.Log.d(
                AUTOFILL_TAG,
                "node hint=${it.getHint()} inputType=${it.getInputType()} " +
                    "tag=${it.getTag()} attrs=${it.getAttributes()}",
            )
        }
        val password = nodes.firstOrNull { isPasswordField(it) }
        if (password == null) {
            android.util.Log.d(AUTOFILL_TAG, "no password field among ${nodes.size} nodes")
            return
        }
        if (!isPasswordField(focused) && !isUsernameField(focused)) {
            android.util.Log.d(AUTOFILL_TAG, "focused node is not a login field")
            return
        }
        val url = tabFor(session)?.url
        if (url == null) {
            android.util.Log.d(AUTOFILL_TAG, "no tab for session")
            return
        }
        val login = loginStore.forOrigin(url).firstOrNull()
        if (login == null) {
            android.util.Log.d(AUTOFILL_TAG, "no stored login for $url")
            return
        }
        val values = android.util.SparseArray<CharSequence>()
        autofillSession.dataFor(password)?.let { values.put(it.id, login.password) }
        nodes.firstOrNull { isUsernameField(it) }?.let { userNode ->
            autofillSession.dataFor(userNode)?.let { values.put(it.id, login.username) }
        }
        android.util.Log.d(AUTOFILL_TAG, "filling ${values.size()} field(s)")
        if (values.size() > 0) {
            runOnUiThread {
                runCatching { autofillSession.autofill(values) }
                    .onFailure { android.util.Log.w(AUTOFILL_TAG, "autofill failed", it) }
            }
        }
    }

    private fun collectNodes(node: Autofill.Node): List<Autofill.Node> {
        val out = mutableListOf<Autofill.Node>()
        fun walk(current: Autofill.Node) {
            out += current
            current.children?.forEach(::walk)
        }
        walk(node)
        return out
    }

    private fun findNode(
        node: Autofill.Node,
        predicate: (Autofill.Node) -> Boolean,
    ): Autofill.Node? {
        if (predicate(node)) return node
        node.children?.forEach { child ->
            findNode(child, predicate)?.let { return it }
        }
        return null
    }

    private fun isPasswordField(node: Autofill.Node): Boolean {
        if (node.getHint() == Autofill.Hint.PASSWORD) return true
        val attrs = node.getAttributes() ?: return false
        return attrs["type"]?.lowercase() == "password" ||
            attrs["autocomplete"]?.lowercase() in listOf("current-password", "new-password")
    }

    private fun isUsernameField(node: Autofill.Node): Boolean {
        if (node.getHint() == Autofill.Hint.USERNAME) return true
        val attrs = node.getAttributes() ?: return false
        val autocomplete = attrs["autocomplete"]?.lowercase().orEmpty()
        if (autocomplete == "username" || autocomplete == "email") return true
        return autocomplete.isEmpty() && attrs["type"]?.lowercase() in listOf("email", "text")
    }

    private val contentBlockingDelegate = object : ContentBlocking.Delegate {
        override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            if (!event.isBlocking) return
            val tab = tabFor(session) ?: return
            // A blocked request is not automatically a tracker. Only
            // anti-tracking blocks count towards the tracker figure; a Safe
            // Browsing verdict is a threat, and a cookie-behaviour block is
            // neither. Mixing them made the privacy report claim a tracker was
            // blocked when a phishing page was.
            when {
                event.getSafeBrowsingCategory() != 0 -> {
                    tab.threats++
                    store.cumulativeThreats = store.cumulativeThreats + 1
                }
                event.getAntiTrackingCategory() != 0 -> {
                    tab.trackers++
                    store.cumulativeTrackers = store.cumulativeTrackers + 1
                }
            }
            if (tab.id == activeId) updateTrackers()
        }
    }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SystemBars.apply(this)
        attachContext(this)
        store = PrivacyStore(this)
        historyStore = HistoryStore(this)
        bookmarkStore = BookmarkStore(this)
        sessionStore = BrowserSessionStore(this)
        sitePermissionStore = SitePermissionStore(this)
        loginStore = LoginStore(this)
        mediaController = BrowserMediaController(this)
        privateIndicator = PrivateBrowsingNotifier(this)

        permissionDelegate = BrowserPermissionDelegate(
            this,
            sitePermissionStore,
            currentOrigin = { session -> session?.let(::tabFor)?.url ?: activeTab()?.url },
            currentPrivate = { session -> session?.let(::tabFor)?.isPrivate ?: (activeTab()?.isPrivate == true) },
        ) { permissions, completion ->
            permissionCompletions.add(completion)
            try {
                permissionLauncher.launch(permissions)
            } catch (_: Exception) {
                val pending = permissionCompletions.toList()
                permissionCompletions.clear()
                pending.forEach { it(false) }
            }
        }
        promptDelegate = BrowserPromptDelegate(
            activity = this,
            launchFilePicker = { filePickerLauncher.launch(it) },
            launchCamera = { cameraLauncher.launch(it) },
            requestCameraPermission = { completion ->
                permissionDelegate.ensureAndroidPermissions(
                    arrayOf(android.Manifest.permission.CAMERA),
                    completion,
                )
            },
            launchShare = { title, text, uri ->
                val shared = listOfNotNull(text?.takeIf { it.isNotBlank() }, uri?.takeIf { it.isNotBlank() })
                    .joinToString("\n")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title.orEmpty())
                    putExtra(Intent.EXTRA_TEXT, shared)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share)))
            },
        )

        if (runtime == null) {
            runtime = createRuntime()
        } else {
            runtime?.setWebNotificationDelegate(BrowserNotificationDelegate(this))
            runtime?.setAutocompleteStorageDelegate(BrowserAutocompleteDelegate(loginStore))
        }

        geckoView = findViewById(R.id.geckoview)
        browserControls = findViewById(R.id.browser_controls)
        homeOverlay = findViewById(R.id.home_overlay)
        progress = findViewById(R.id.progress)
        addressText = findViewById(R.id.address_text)
        tabsCount = findViewById(R.id.tabs_count)
        geckoView.setAutofillEnabled(true)
        findViewById<View>(R.id.btn_pip).setOnClickListener { enterPictureInPicture() }

        findViewById<View>(R.id.engine_picker).setOnClickListener { showEnginePicker() }
        updateEngineBadge()
        // The home shortcuts are not rendered here. This runs before the first
        // navigation and it parses the whole history and bookmark JSON, then does
        // a nested history-x-bookmark scan, all on the main thread, for an overlay
        // that is still hidden. On a launch that carries a URL -- tapping a link in
        // another app, say -- that overlay is never shown, so the work is pure
        // waste in front of the first page render. selectTab renders the
        // shortcuts when it actually reveals the overlay instead.
        addressText.setOnEditorActionListener { v, _, _ ->
            hideSuggestions()
            navigate((v as EditText).text.toString())
            // clearFocus() alone does not always take the keyboard down: the
            // editor keeps its window token until the IME is explicitly told, so
            // the result loads behind a keyboard the user just dismissed.
            addressText.clearFocus()
            // The IME holds its own window token, so clearing focus on the field
            // is not enough to take the keyboard down; the result would load
            // behind a keyboard the user has just dismissed.
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(addressText.windowToken, 0)
            true
        }
        addressText.setOnFocusChangeListener { _, focused ->
            if (focused) {
                addressText.selectAll()
                updateSuggestions(addressText.text.toString())
            } else {
                hideSuggestions()
                updateAddress()
            }
        }
        // No long-press listener on the address field on purpose. Swallowing the
        // long press replaced the platform's own text menu, so a user who wanted
        // to select, cut or paste part of a URL could not. The page actions that
        // used to live here are in the menu sheet instead, where they are
        // reachable without a gesture that collides with text editing.
        addressText.doAfterTextChanged { text ->
            if (addressText.hasFocus()) updateSuggestions(text?.toString().orEmpty())
        }
        val voice = findViewById<View>(R.id.btn_voice)
        voice.visibility = if (store.voiceSearch) View.VISIBLE else View.GONE
        voice.setOnClickListener { startVoiceSearch() }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.suggestions_list).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = SuggestionsAdapter(emptyList()).also { suggestionsAdapter = it }
        }
        tabsCount.setOnClickListener { showTabsSheet() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMenuSheet() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = activeTab()
                if (fullscreenSession != null) {
                    setBrowserFullscreen(tab?.session ?: return, false)
                } else if (tab != null && !tab.isHome && tab.canGoBack) {
                    tab.session.goBack()
                }
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        restoreTabsIfAvailable()
        maybeStartOnboarding()
        handleIntent(intent)
    }

    /**
     * Puts the first-run walkthrough in front of the browser once.
     *
     * The browser keeps initialising underneath, because unwinding that would mean
     * a half-built activity sitting in the back stack, and the walkthrough is one
     * activity wide. An incoming link is therefore not lost: `handleIntent` still
     * runs, and the tab is there the moment the walkthrough closes.
     */
    private fun maybeStartOnboarding() {
        if (!OnboardingActivity.isNeeded(this)) return
        startActivity(Intent(this, OnboardingActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_WEB_APP)?.let { appUrl ->
            intent.removeExtra(EXTRA_WEB_APP)
            webAppOrigin = runCatching { Uri.parse(appUrl).host }.getOrNull()
        }
        intent.getStringExtra(EXTRA_NOTIFICATION_URL)?.let { notificationUrl ->
            intent.removeExtra(EXTRA_NOTIFICATION_URL)
            if (tabs.isEmpty()) newTab(isPrivate = false, select = true)
            navigate(notificationUrl)
            return
        }
        if (intent.action == ACTION_DATA_CLEARED) {
            sanitize(keepPage = false)
            return
        }
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            openSharedText(intent)
            return
        }
        when (intent.action) {
            ACTION_PRIVATE_TAB -> {
                newTab(isPrivate = true, select = true)
                return
            }
            ACTION_ABOUT -> {
                if (tabs.isEmpty()) newTab(isPrivate = false, select = true)
                showAbout()
                return
            }
        }
        val data = intent.data?.toString()
        if (tabs.isEmpty()) newTab(isPrivate = false, select = true)
        // Every scheme lands in navigate(), which owns the URL-vs-search
        // decision and the external-scheme handoff. (This used to be a
        // three-way branch with navigate(data) in all three arms.)
        if (data != null) {
            navigate(data)
        } else if (store.homepage != HOME_START && activeTab()?.isHome == true) {
            navigate(store.homepage)
        }
    }

    /**
     * Share-into-the-browser. Plain text, HTML, a subject line and multiple
     * shared items are all accepted; the first usable value is what gets opened,
     * so a share from a mail or notes app lands on the right page.
     */
    private fun openSharedText(intent: Intent) {
        val extras = mutableListOf<CharSequence>()
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { extras += it }
        @Suppress("DEPRECATION")
        intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.let { extras += it }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
        val body = extras.joinToString("\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .trim()
        val payload = listOf(body, subject)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (payload.isBlank()) return
        if (tabs.isEmpty()) newTab(isPrivate = false, select = true)
        if (body.isNotBlank()) navigate(body) else navigate(subject)
    }

    private fun restoreTabsIfAvailable() {
        if (tabs.isNotEmpty()) return
        val restored = sessionStore.tabs()
        if (restored.isEmpty()) return
        val created = restored.map { saved ->
            newTab(isPrivate = false, select = false).also { tab ->
                tab.isHome = false
                tab.url = saved.url
                tab.title = saved.title
                tab.lastActiveAt = saved.lastActiveAt
            }
        }
        val activeUrl = sessionStore.activeUrl()
        val selected = created.firstOrNull { it.url == activeUrl } ?: created.first()
        // Only the tab in front loads now; the rest load on first selection
        // (selectTab honors needsLoad). A cold start with many tabs used to
        // fire every navigation at once before the first page could render.
        created.forEach { if (it.id != selected.id) it.needsLoad = true }
        selected.session.loadUri(selected.url)
        selectTab(selected.id)
    }

    private fun saveTabState() {
        if (!::sessionStore.isInitialized) return
        sessionStore.saveTabs(
            tabs.filter {
                !it.isPrivate && !it.isHome && !it.isError && it.url.isNotBlank() &&
                    !it.url.startsWith("data:") && it.url != "about:blank"
            }.map {
                RestorableTab(it.url, it.title, it.lastActiveAt)
            },
            activeTab()?.takeIf { !it.isPrivate && !it.isHome }?.url,
        )
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        saveTabState()
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        saveTabState()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // History and Bookmarks screens edit their stores while this activity
        // is stopped, so the suggestion cache is dropped on the way back in.
        // Without this, suggestions would show entries the user just deleted.
        suggestionHistory = null
        suggestionBookmarks = null
        // Tapping or swiping the private-browsing reminder is only observable
        // here, on the way back into the app, so this is where the dismissal is
        // noticed. It also covers a launch that restored tabs without going
        // through selectTab.
        if (::privateIndicator.isInitialized) {
            privateIndicator.onResume(activeTab()?.isPrivate == true)
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching { runtime?.storageController?.clearData(StorageController.ClearFlags.ALL_CACHES) }
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            suspendBackgroundTabs()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        suspendBackgroundTabs()
    }

    /**
     * Memory pressure. The tab list is kept intact and the active tab plus the
     * most recently used background tabs keep their sessions; everything older
     * loses its Gecko session, so the tray, history and recently-closed still
     * work. Selecting a suspended tab reloads its URL (reviveTab). The `keep`
     * parameter is the total number of live sessions, active tab included.
     */
    private fun suspendBackgroundTabs(keep: Int = 3) {
        if (!::geckoView.isInitialized) return
        val suspendable = tabs.filter {
            it.id != activeId && !it.isHome && !it.isPrivate && !it.suspended && it.url.isNotBlank()
        }.sortedByDescending { it.lastActiveAt }
        // Active tab always survives; keep-1 most recent background tabs join
        // it. The old arithmetic (budget = size - keep) closed almost nothing
        // on a critical trim with a normal tab count, which is exactly when
        // memory has to be freed.
        val survivors = suspendable.take((keep - 1).coerceAtLeast(0)).map { it.id }.toSet()
        suspendable.filter { it.id !in survivors }.forEach { tab ->
            runCatching { tab.session.close() }
            tab.suspended = true
        }
        saveTabState()
    }

    private fun reviveTab(tab: Tab) {
        if (!tab.suspended) return
        tab.suspended = false
        tab.session = createSession(isPrivate = tab.isPrivate)
        if (tab.url.isNotBlank()) tab.session.loadUri(tab.url)
    }

    /** Engine settings built from LibreWolf desktop defaults. Applied at startup. */
    private fun buildRuntimeSettings(): GeckoRuntimeSettings {
        val dohProviders = LibreWolfDefaults.DOH_PROVIDERS
        val doh = dohProviders[store.dohProvider.coerceIn(dohProviders.indices)]
        val cbBuilder = ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(
                if (store.etpStrict) ContentBlocking.EtpLevel.STRICT
                else ContentBlocking.EtpLevel.DEFAULT,
            )
            .strictSocialTrackingProtection(store.blockSocialTrackers)
            .cookieBehavior(
                when {
                    store.blockAllCookies -> ContentBlocking.CookieBehavior.ACCEPT_NONE
                    store.blockThirdPartyCookies -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                    else -> ContentBlocking.CookieBehavior.ACCEPT_ALL
                },
            )
            .cookieBehaviorPrivateMode(
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS,
            )
            .cookiePurging(true)
            .allowListBaselineTrackingProtection(true)
            .allowListConvenienceTrackingProtection(false)
            .queryParameterStrippingEnabled(true)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .queryParameterStrippingStripList(*LibreWolfDefaults.QUERY_STRIP_LIST.toTypedArray())
            .queryParameterStrippingAllowList(LibreWolfDefaults.QUERY_STRIP_ALLOW)
            .safeBrowsing(
                if (store.safeBrowsingMode == 0) ContentBlocking.SafeBrowsing.NONE
                else ContentBlocking.SafeBrowsing.DEFAULT,
            )
        if (store.safeBrowsingMode == 0) cbBuilder.safeBrowsingProviders()
        val cb = cbBuilder.build()
        val trrMode = when (store.dohMode) {
            1 -> GeckoRuntimeSettings.TRR_MODE_FIRST
            2 -> GeckoRuntimeSettings.TRR_MODE_ONLY
            else -> GeckoRuntimeSettings.TRR_MODE_DISABLED
        }
        return GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(store.javascriptEnabled)
            .contentBlocking(cb)
            .allowInsecureConnections(
                when (store.httpsMode) {
                    1 -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE
                    2 -> GeckoRuntimeSettings.HTTPS_ONLY
                    else -> GeckoRuntimeSettings.ALLOW_ALL
                },
            )
            .trustedRecursiveResolverUri(doh.uri)
            .trustedRecursiveResolverMode(trrMode)
            .globalPrivacyControlEnabled(store.doNotTrack)
            .loginAutofillEnabled(true)
            .crashHandler(GeckoCrashService::class.java)
            .crashPullNeverShowAgain(true)
            // Never in an ordinary build. A debuggable build offers the switch
            // so a tester can attach DevTools; a release build ignores the
            // stored preference entirely, so a setting flipped on a debug build
            // and carried over by a reinstall cannot open the socket.
            .remoteDebuggingEnabled(BuildConfig.DEBUG && store.remoteDebugging)
            .setLnaEnabled(true)
            .setLnaBlocking(true)
            .setLnaBlockTrackers(true)
            .build()
    }

    private fun createRuntime(): GeckoRuntime {
        val rt = GeckoRuntime.create(this, buildRuntimeSettings())
        rt.settings.cookieBehaviorOptInPartitioning = true
        rt.setAutocompleteStorageDelegate(BrowserAutocompleteDelegate(loginStore))
        rt.setWebNotificationDelegate(BrowserNotificationDelegate(this))
        rt.setServiceWorkerDelegate(object : GeckoRuntime.ServiceWorkerDelegate {
            override fun onOpenWindow(url: String): GeckoResult<GeckoSession> =
                GeckoResult.fromValue(createUnopenedPopup(isPrivate = false))
        })
        applyEnginePreferences()
        applyPrivacyOverrides(rt)
        registerDownloadRefresher()
        return rt
    }

    /**
     * Fingerprinting protection, applied to the live runtime.
     *
     * The builder does not expose these, so they are set on the runtime after
     * creation. Standard and strict differ by the engine preference rather than
     * by the API: standard reduces what a site can read back, strict turns on
     * full resist-fingerprinting, which also removes APIs a site uses to detect
     * an automated browser and will break some sign-in flows. That is the
     * trade the settings screen describes.
     */
    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private fun applyPrivacyOverrides(rt: GeckoRuntime) {
        val enabled = store.fingerprintingMode != 2
        runCatching { rt.settings.setFingerprintingProtection(enabled) }
        runCatching { rt.settings.setBaselineFingerprintingProtection(enabled) }
    }

    /**
     * Engine preferences the runtime builder does not expose.
     *
     * `pdfjs.disabled` matters for downloads: with the built-in PDF viewer on,
     * GeckoView consumes an `application/pdf` response inside the page and the
     * embedder never sees it, so a PDF is neither saved by the download engine
     * nor checked by the download-safety warning. With it off, the response is
     * handed over like any other file.
     */
    // GeckoView marks this pref setter experimental through the AndroidX opt-in
    // marker, which needs the androidx annotation rather than Kotlin's @OptIn.
    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private fun applyEnginePreferences() {
        setGeckoPref("pdfjs.disabled", true)
        // Strict is the only mode that turns on full resist-fingerprinting, which
        // is why the settings screen warns that it can break sites.
        setGeckoPref("privacy.resistFingerprinting", store.fingerprintingMode == 1)
        // A floor under rendered text. 0 means "no floor", which is the default.
        setGeckoPref("font.minimum-size.x-western", store.minimumFontSize.toString())
        // Keeps WebRTC from handing a site the addresses of local interfaces.
        setGeckoPref("media.peerconnection.ice.default_address_only", store.webrtcDefaultRouteOnly)
    }

    // GeckoView marks this pref setter experimental through the AndroidX opt-in
    // marker, which needs the androidx annotation rather than Kotlin's @OptIn.
    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private fun setGeckoPref(name: String, value: Boolean) {
        GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER).accept({ }, { })
    }

    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private fun setGeckoPref(name: String, value: String) {
        GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER).accept({ }, { })
    }

    /**
     * Lets a paused download continue. GeckoView owns the cookie jar and offers
     * no way to read it, so the only way to re-open an authenticated file is to
     * let Gecko fetch it again in a throwaway session and hand us the body; the
     * engine then skips the bytes it already has.
     */
    private fun registerDownloadRefresher() {        DownloadEngine.setEngineRefresher { url, onBody ->
            val rt = runtimeRef()
            if (rt == null) {
                onBody(null, -1L)
                return@setEngineRefresher
            }
            var delivered = false
            val session = GeckoSession(GeckoSessionSettings.Builder().build())
            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    if (delivered) return
                    delivered = true
                    val length = response.headers.entries
                        .firstOrNull { it.key.equals("Content-Length", true) }
                        ?.value?.toLongOrNull() ?: -1L
                    onBody(response.body, length)
                    runCatching { session.close() }
                }
            }
            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    // A refresh that renders instead of downloading (error page,
                    // expired session) must not leave the task stuck.
                    if (delivered || success) return
                    delivered = true
                    onBody(null, -1L)
                    runCatching { session.close() }
                }
            }
            runCatching {
                session.open(rt)
                session.loadUri(url)
            }.onFailure {
                if (!delivered) {
                    delivered = true
                    onBody(null, -1L)
                }
            }
            // GeckoView 147 has no load-error callback, so bound the wait instead
            // of letting a dead refresh strand the task in "Queued" forever.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (delivered) return@postDelayed
                delivered = true
                onBody(null, -1L)
                runCatching { session.close() }
            }, REFRESH_TIMEOUT_MS)
        }
    }

    // ---- tabs ----

    private fun createSession(isPrivate: Boolean, open: Boolean = true): GeckoSession {
        val settings = GeckoSessionSettings.Builder().apply {
            usePrivateMode(isPrivate)
            allowJavascript(store.javascriptEnabled)
            // Background audio is the same engine switch as "suspend media when
            // this session is not in front", so the setting maps to it directly.
            suspendMediaWhenInactive(!store.backgroundMedia)
            if (store.desktopMode) {
                userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            }
            if (store.spoofDesktopUserAgent) {
                // Some banks and streaming services refuse anything that is not a
                // common desktop browser, even when desktop mode is already on.
                // This is the same string Firefox sends when a user overrides the
                // agent by hand, so it is a compatibility switch and not a
                // fingerprint one; the screen says so.
                userAgentOverride(MICROMIUM_DESKTOP_UA)
            }
        }.build()
        val session = GeckoSession(settings)
        session.contentDelegate = contentDelegate
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        session.contentBlockingDelegate = contentBlockingDelegate
        session.promptDelegate = promptDelegate
        session.permissionDelegate = permissionDelegate
        session.autofillDelegate = autofillDelegate
        session.selectionActionDelegate = BasicSelectionActionDelegate(this)
        session.mediaSessionDelegate = mediaController.delegate
        if (open) session.open(runtime ?: error("GeckoRuntime is not initialized"))
        return session
    }

    private fun createUnopenedPopup(isPrivate: Boolean): GeckoSession {
        val popup = Tab(
            id = nextId++,
            session = createSession(isPrivate = isPrivate, open = false),
            isPrivate = isPrivate,
            isHome = false,
            // A window the user asked for can take focus; one a site opened on
            // its own waits in the background so it does not interrupt.
            selectWhenOpened = store.newTabsInForeground,
        )
        tabs.add(popup)
        updateBadge()
        tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
        return popup.session
    }

    private fun newTab(isPrivate: Boolean, select: Boolean): Tab {
        applyCloseTabsPolicy()
        val tab = Tab(id = nextId++, session = createSession(isPrivate), isPrivate = isPrivate)
        tabs.add(tab)
        if (select) {
            selectTab(tab.id)
            if (store.homepage != HOME_START) navigate(store.homepage)
        } else {
            updateBadge()
        }
        return tab
    }

    /** Closes tabs older than the configured retention window (Close tabs setting). */
    private fun applyCloseTabsPolicy() {
        val days = store.closeTabsAfterDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val stale = tabs.filter { !it.isPrivate && it.lastActiveAt < cutoff }
        for (tab in stale) {
            if (tab.id == activeId) continue
            tabs.remove(tab)
            tab.session.close()
        }
    }

    /** Tabs unviewed for 14+ days when "Move old tabs to inactive" is enabled. */
    private fun inactiveIds(): Set<Long> {
        if (!store.moveOldTabsToInactive) return emptySet()
        val cutoff = System.currentTimeMillis() - 14 * 86_400_000L
        return tabs.filter { !it.isPrivate && it.lastActiveAt < cutoff }.map { it.id }.toSet()
    }

    private fun selectTab(id: Long) {
        val tab = tabs.find { it.id == id } ?: return
        activeId = id
        // Every path that changes which tab is in front goes through here, so
        // this is the one place the private-browsing reminder has to be right.
        privateIndicator.sync(tab.isPrivate)
        tab.lastActiveAt = System.currentTimeMillis()
        reviveTab(tab)
        // A tab left in an error state -- crashed content process, failed load
        // -- shows a dead page with no way back except this. Retrying on
        // selection is a single load, not a loop: nothing here re-invokes
        // selectTab, and a fresh failure simply re-marks the tab.
        if (tab.needsLoad) {
            tab.needsLoad = false
            if (tab.url.isNotBlank()) tab.session.loadUri(tab.url)
        } else if (tab.isError && !tab.isHome && tab.url.isNotBlank() &&
            !tab.url.startsWith("data:")
        ) {
            tab.isError = false
            tab.session.reload()
        }
        for (candidate in tabs) {
            val selected = candidate.id == id
            if (!candidate.suspended && candidate.session.isOpen) {
                candidate.session.setActive(selected)
                candidate.session.setFocused(selected)
            }
        }
        geckoView.setSession(tab.session)
        val home = tab.isHome
        homeOverlay.visibility = if (home) View.VISIBLE else View.GONE
        geckoView.visibility = if (home) View.GONE else View.VISIBLE
        // selectTab is what reveals the home overlay on a fresh launch, so it is
        // also the only place the shortcuts can be rendered for that case:
        // showHomeState's path depends on a blank-URL page start, which never
        // happens when nothing is loaded. Rendering here rather than in onCreate
        // keeps the parsing off the startup path for the common case of opening
        // the browser on a URL, where the overlay is never shown at all.
        updateAddress()
        updateEngineBadge()
        updateTrackers()
        updateBadge()
        tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
    }

    private fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index == -1) return
        val removed = tabs[index]
        if (!removed.isPrivate && !removed.isHome && removed.url.isNotBlank()) {
            sessionStore.addClosed(removed.url, removed.title)
        }
        if (id == activeId && tabs.size > 1) {
            selectTab(tabs[if (index == tabs.size - 1) index - 1 else index + 1].id)
        }
        // True when the replacement below is the only tab left standing, so
        // there is no real browsing session that a wipe could disturb.
        val emptied = tabs.size == 1
        if (emptied) {
            // The last tab is going, so something has to take its place. That is
            // a normal tab even when the one being closed was private: closing
            // the last private tab is how private browsing is left, and quietly
            // replacing it with another private tab would make it impossible to
            // get out of by closing tabs.
            newTab(isPrivate = false, select = true)
        }
        tabs.removeAt(tabs.indexOfFirst { it.id == id })
        removed.session.close()
        updateBadge()
        // Closing the session is what discards what the private tab held. The
        // broad clear is only safe when other tabs survive it, and it is skipped
        // for the emptied case: there the user closed a tab, and answering that
        // by wiping their cookies and logging them out of everything would be a
        // surprise nobody asked for.
        if (removed.isPrivate && !emptied && tabs.none { it.isPrivate }) sanitize(keepPage = true)
        tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
    }

    private fun closeAllTabs(privateOnly: Boolean) {
        val victims = tabs.filter { it.isPrivate == privateOnly }
        if (victims.isEmpty()) return
        val activeRemoved = victims.any { it.id == activeId }
        for (tab in victims) {
            if (!tab.isPrivate && !tab.isHome && tab.url.isNotBlank()) {
                sessionStore.addClosed(tab.url, tab.title)
            }
            tabs.remove(tab)
            tab.session.close()
        }
        val emptied = tabs.isEmpty()
        if (emptied) {
            // Same reasoning as closing the last private tab one at a time:
            // clearing the private tray should land the user in normal browsing.
            newTab(isPrivate = false, select = true)
        } else if (activeRemoved) {
            selectTab(tabs[0].id)
        } else {
            updateBadge()
        }
        // As in closeTab: the broad clear only runs when a real session
        // survived it, not when the tray was emptied and refilled.
        if (privateOnly && !emptied && tabs.none { it.isPrivate }) sanitize(keepPage = true)
        tabsSheet?.refresh(snapshot(), snapshot().filter { it.id in inactiveIds() }, trayTrackersText)
    }

    private fun snapshot(): List<TabItem> =
        tabs.map {
            TabItem(
                it.id,
                it.title,
                it.url,
                it.isPrivate,
                it.id == activeId,
                it.isLoading,
                if (it.isPrivate) null else FaviconCache.cached(it.url),
            )
        }

    /**
     * The count on the tab button.
     *
     * It counts the kind of tab that is in front, not every tab: with a private
     * tab active it is the number of private tabs, otherwise the number of
     * normal ones. A total would be a number the user cannot act on, and in a
     * private window it is the single most misleading moment to be imprecise
     * about how many normal tabs are open alongside it.
     */
    private fun updateBadge() {
        val privateActive = activeTab()?.isPrivate == true
        tabsCount.text = tabs.count { it.isPrivate == privateActive }.toString()
    }

    private fun updateTrackers() {
        val tab = activeTab()
        val threats = tab?.threats ?: 0
        val text = when {
            threats > 0 -> resources.getQuantityString(R.plurals.threats_blocked, threats, threats)
            else -> {
                val n = tab?.trackers ?: 0
                resources.getQuantityString(R.plurals.trackers_blocked, n, n)
            }
        }
        // The start page no longer carries a tracker pill; the figure lives on the
        // tab tray, which is where a tab in front is actually identified.
        trackersText?.text = text
        trayTrackersText = text
    }

    // ---- navigation ----

    private fun navigate(input: String) {
        val tab = activeTab() ?: return
        val text = input.trim()
        if (text.isEmpty()) return
        if (text.startsWith("mailto:") || text.startsWith("tel:") || text.startsWith("geo:") ||
            text.startsWith("market:") || text.startsWith("intent:")
        ) {
            openExternalUri(text)
            return
        }
        if (text.startsWith("data:")) {
            tab.session.loadUri(text)
            tab.isHome = false
            tab.isError = false
            homeOverlay.visibility = View.GONE
            geckoView.visibility = View.VISIBLE
            hideSuggestions()
            addressText.clearFocus()
            return
        }
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            // A blank page is a shell state, not a navigation: routing it through
            // the engine would produce an about:blank location commit that the
            // delegate then has to treat as meaningless. goHome covers this by
            // showing the overlay with no engine round-trip.
            isBlank(text) -> {
                showHomeState(tab)
                hideSuggestions()
                addressText.clearFocus()
                return
            }
            text.startsWith("about:") -> text
            text.startsWith("//") -> "https:$text"
            text == "localhost" || text.startsWith("localhost:") ||
                text.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?")) ||
                (text.contains(".") && !text.contains(" ")) -> "https://$text"
            else -> {
                navigateSearch(text)
                return
            }
        }
        tab.isHome = false
        tab.isError = false
        homeOverlay.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        hideSuggestions()
        tab.session.loadUri(url)
    }

    private fun navigateSearch(query: String): Boolean {
        val tab = activeTab() ?: return false
        val engine = store.engineFor(tab.isPrivate)
        tab.isHome = false
        tab.isError = false
        homeOverlay.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        hideSuggestions()
        addressText.clearFocus()
        tab.session.loadUri(engine.queryUrl.format(URLEncoder.encode(query, "UTF-8")))
        return true
    }

    private fun showHomeState(tab: Tab) {
        tab.isHome = true
        tab.isError = false
        tab.url = ""
        tab.title = ""
        if (tab.id != activeId) return
        homeOverlay.visibility = View.VISIBLE
        geckoView.visibility = View.GONE
        progress.visibility = View.GONE
        updateAddress()
        updateTrackers()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isBlank(url: String): Boolean =
        url.isEmpty() || url == "about:blank" || url == "about:home" || url == HOME_START

    private fun goHome() {
        val tab = activeTab() ?: return
        showHomeState(tab)
    }

    private fun updateAddress() {
        val tab = activeTab()
        if (tab == null || tab.isHome) {
            if (!addressText.hasFocus()) addressText.setText("")
            addressText.hint = getString(R.string.url_hint)
        } else {
            if (!addressText.hasFocus()) addressText.setText(tab.title.ifEmpty { tab.url })
            addressText.hint = tab.url
        }
    }

    private fun openUrlInNewTab(url: String, select: Boolean) {
        if (url.isBlank()) return
        val current = activeTab()
        val tab = newTab(isPrivate = current?.isPrivate == true, select = select)
        tab.isHome = false
        if (select) {
            homeOverlay.visibility = View.GONE
            geckoView.visibility = View.VISIBLE
        }
        tab.session.loadUri(url)
    }

    private fun copyText(text: String, label: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun showContextMenu(
        session: GeckoSession,
        element: GeckoSession.ContentDelegate.ContextElement,
    ) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val link = element.linkUri?.takeIf { it.isNotBlank() }
        val source = element.srcUri?.takeIf { it.isNotBlank() }
        if (link != null) {
            actions += getString(R.string.open_in_new_tab) to { openUrlInNewTab(link, true) }
            actions += getString(R.string.open_in_background_tab) to { openUrlInNewTab(link, false) }
            actions += getString(R.string.copy_link) to { copyText(link, getString(R.string.copy_link)) }
            actions += getString(R.string.share) to { shareUri(link) }
        }
        if (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && source != null) {
            actions += getString(R.string.open_image) to { openUrlInNewTab(source, true) }
            actions += getString(R.string.download_image) to { enqueueDownload(source) }
            actions += getString(R.string.copy_image_address) to { copyText(source, getString(R.string.copy_image_address)) }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(element.title?.takeIf { it.isNotBlank() } ?: getString(R.string.page_actions))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun shareUri(uri: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, uri)
                },
                getString(R.string.share),
            ),
        )
    }

    private fun showContentProcessFailure(session: GeckoSession, message: Int) {
        val tab = tabFor(session) ?: return
        // Mark it even when only a Toast is shown (background tab): without the
        // flag the tab keeps a dead session with no recovery path, and
        // selecting it later would show a blank page. selectTab retries
        // error-flagged tabs, which is what recovers them.
        tab.isError = true
        if (tab.id != activeId || isFinishing || isDestroyed) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.page_problem)
            .setMessage(message)
            .setNegativeButton(R.string.close) { _, _ -> closeTab(tab.id) }
            .setPositiveButton(R.string.reload) { _, _ -> tab.session.reload() }
            .show()
    }

    internal fun setBrowserFullscreen(session: GeckoSession, enabled: Boolean) {
        fullscreenSession = if (enabled) session else null
        if (!enabled) chromeRevealed = false
        applyChromeVisibility()
    }

    private fun setSystemBarsVisible() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.show(WindowInsetsCompat.Type.systemBars())
        SystemBars.refresh(this)
    }

    private fun pictureInPictureParams(): PictureInPictureParams {
        val aspect = mediaController.aspectRatio().takeIf { it.isFinite() && it > 0.2 } ?: 16.0 / 9.0
        // The platform only accepts an integer Rational, and only between
        // 1/2.39 and 2.39.
        val clamped = aspect.coerceIn(0.42, 2.39)
        val denominator = (1000 / clamped).toInt().coerceIn(1, 4000)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(1000, denominator))
            .apply {
                if (geckoView.width > 0 && geckoView.height > 0) {
                    setSourceRectHint(android.graphics.Rect(0, 0, geckoView.width, geckoView.height))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(true)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private fun enterPictureInPicture() {
        if (!store.pictureInPicture) return
        if (fullscreenSession == null || isInPictureInPictureMode) return
        runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
            .onFailure { Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (fullscreenSession != null) enterPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            browserControls.visibility = View.GONE
            runCatching { setPictureInPictureParams(pictureInPictureParams()) }
        } else {
            applyChromeVisibility()
        }
    }

    /**
     * The Activity handles rotation itself, so the window has to be re-inset and
     * the PiP aspect ratio recomputed whenever the configuration changes.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        SystemBars.refresh(this)
        if (isInPictureInPictureMode) {
            runCatching { setPictureInPictureParams(pictureInPictureParams()) }
        }
    }

    /** Engine badge in the toolbar pill reflects the active (normal/private) engine. */
    private fun updateEngineBadge() {
        val badge = findViewById<TextView>(R.id.engine_badge) ?: return
        val engine = store.engineFor(activeTab()?.isPrivate == true)
        badge.text = badgeLetter(engine.name, engine.name)
        badge.background?.setTint(badgeColorFor(engine.name))
    }

    private fun showEnginePicker() {
        val sheet = EnginePickerSheet().apply {
            onPick = { engine ->
                store.customEngine = null
                if (LibreWolfDefaults.SEARCH_ENGINES.any { it.name == engine.name }) {
                    store.searchEngineName = engine.name
                } else {
                    store.customEngine = engine.name
                }
                updateEngineBadge()
            }
            onAddCustom = {
                startActivity(Intent(this@MainActivity, DefaultSearchActivity::class.java))
            }
        }
        sheet.show(supportFragmentManager, "engines")
    }

    // ---- external apps / downloads ----

    private fun openExternalUri(uri: String) {
        if (!store.openLinksInApps) return
        // An empty or unparsable URI would launch a content-less VIEW intent and
        // fail with an opaque system error.
        if (uri.isBlank() || Uri.parse(uri).scheme.isNullOrBlank()) return
        try {
            val intent = if (uri.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueDownload(
        url: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
        mimeType: String? = null,
        body: java.io.InputStream? = null,
        contentLength: Long = -1L,
    ) {
        // This runs inside a Gecko delegate callback, so nothing may escape: an
        // exception here would take the callback, and with it the page load,
        // down with it.
        runCatching {
            if (url.isBlank()) return@runCatching
            val contentDisposition = headers.entries.firstOrNull {
                it.key.equals("Content-Disposition", true)
            }?.value
            val guessedName = DownloadSafety.fileNameFrom(contentDisposition, url, mimeType)
            val verdict = if (store.warnRiskyDownloads) {
                DownloadSafety.inspect(url, guessedName, mimeType, store.httpsMode != 0)
            } else {
                null
            }
            if (verdict != null) {
                confirmRiskyDownload(guessedName, verdict) {
                    startDownload(url, headers, userAgent, mimeType, guessedName, body, contentLength)
                }
            } else {
                startDownload(url, headers, userAgent, mimeType, guessedName, body, contentLength)
            }
        }.onFailure {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Executables and unencrypted transfers are confirmed explicitly. Dismissing
     * the dialog cancels the download, so a stray link cannot install anything.
     */
    private fun confirmRiskyDownload(name: String, verdict: DownloadSafety.Verdict, onAccept: () -> Unit) {
        val reason = when (verdict.risk) {
            DownloadSafety.Risk.EXECUTABLE -> getString(R.string.download_risk_executable, verdict.detail)
            DownloadSafety.Risk.INSECURE -> getString(R.string.download_risk_insecure, verdict.detail)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_risk_title)
            .setMessage(getString(R.string.download_risk_message, name, reason))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download_risk_continue) { _, _ -> onAccept() }
            .show()
    }

    private fun startDownload(
        url: String,
        headers: Map<String, String>,
        userAgent: String?,
        mimeType: String?,
        guessedName: String,
        body: java.io.InputStream?,
        contentLength: Long,
    ) {
        try {
            val folder = store.downloadFolder.trim('/')
            val name = uniqueDownloadName(guessedName, folder)
            val taskHeaders = mutableMapOf<String, String>()
            headers.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank() &&
                    !key.equals("Content-Length", true) &&
                    !key.equals("Host", true) &&
                    !key.equals("Connection", true)
                ) {
                    taskHeaders[key] = value
                }
            }
            // GeckoView owns the cookie jar and exposes no way to read it, so an
            // engine-issued request could never be authenticated. The cookie
            // below only helps the rare case where a download starts without a
            // Gecko response body; it is not the primary mechanism.
            android.webkit.CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { taskHeaders["Cookie"] = it }
            if (body != null) {
                DownloadEngine.enqueueFromEngine(
                    url = url,
                    name = name,
                    mime = mimeType.orEmpty(),
                    folder = folder,
                    headers = taskHeaders,
                    body = body,
                    totalBytes = contentLength,
                )
            } else {
                DownloadEngine.enqueue(
                    url = url,
                    name = name,
                    mime = mimeType.orEmpty(),
                    folder = folder,
                    headers = taskHeaders,
                    userAgent = userAgent.orEmpty(),
                )
            }
            Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.not_available), Toast.LENGTH_SHORT).show()
        }
    }

    private fun uniqueDownloadName(name: String, folder: String = ""): String {
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        val directory = if (folder.isBlank()) downloads else java.io.File(downloads, folder)
        val safeName = name.ifBlank { "download" }
        if (!java.io.File(directory, safeName).exists()) return safeName
        val base = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while (true) {
            val candidate = "$base ($index)$extension"
            if (!java.io.File(directory, candidate).exists()) return candidate
            index++
        }
    }

    // ---- UI: sheets / dialogs ----

    private fun showMenuSheet() {
        val sheet = MenuSheet().apply {
            onHistory = { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
            onBookmarks = { startActivity(Intent(this@MainActivity, BookmarksActivity::class.java)) }
            onDownloads = { startActivity(Intent(this@MainActivity, DownloadsActivity::class.java)) }
            onSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            // The compatibility page is about *this* site, so the active URL
            // travels with the intent. Without it the page could only offer a
            // generic explanation and the user would have to work out which site
            // they were looking at.
            onCompatibility = {
                startActivity(
                    Intent(this@MainActivity, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_SCREEN, SettingsScreens.COMPATIBILITY)
                        .putExtra(SettingsActivity.EXTRA_SITE, activeTab()?.url.orEmpty()),
                )
            }
            onQuit = { askQuit() }
            onPrivacyReport = { showPrivacyReport() }
            canInstallPwa = webAppManifest != null && webAppUrl != null
            onInstallPwa = { installWebApp() }
            isHomepage = activeTab()?.isHome ?: true
            pageActions = pageActions()
        }
        menuSheet = sheet
        sheet.show(supportFragmentManager, "menu")
    }

    private fun installWebApp() {
        val manifest = webAppManifest ?: return
        val pageUrl = webAppUrl ?: return
        val rawStart = manifest.optString("start_url").ifBlank { "/" }
        val startUrl = runCatching { java.net.URI(pageUrl).resolve(rawStart).toString() }.getOrDefault(pageUrl)
        if (!startUrl.startsWith("https://") && !startUrl.startsWith("http://")) return
        val name = manifest.optString("short_name").ifBlank {
            manifest.optString("name").ifBlank { Uri.parse(pageUrl).host.orEmpty() }
        }
        if (name.isBlank()) return
        val launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(startUrl)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Marks this as a launch of the installed web app, which is what
            // lets the shell drop the browser chrome for it.
            putExtra(EXTRA_WEB_APP, startUrl)
        }
        try {
            val manager = getSystemService(android.content.pm.ShortcutManager::class.java)
            val icon = webAppIconUrl
                ?.let { FaviconCache.cachedImage(it) }
                ?.let { android.graphics.drawable.Icon.createWithBitmap(it) }
                ?: android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher)
            val shortcut = android.content.pm.ShortcutInfo.Builder(this, "webapp:${Uri.parse(pageUrl).host}")
                .setShortLabel(name.take(30))
                .setLongLabel(name)
                .setIcon(icon)
                .setIntent(launchIntent)
                .build()
            val accepted = manager.requestPinShortcut(shortcut, null)
            Toast.makeText(
                this,
                if (accepted) R.string.app_shortcut_requested else R.string.app_shortcut_failed,
                Toast.LENGTH_SHORT,
            ).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.app_shortcut_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Largest usable icon from a web app manifest, resolved against the page URL. */
    private fun webAppIcon(manifest: org.json.JSONObject, pageUrl: String): String? {
        val icons = manifest.optJSONArray("icons") ?: return null
        var best: Pair<Int, String>? = null
        for (i in 0 until icons.length()) {
            val entry = icons.optJSONObject(i) ?: continue
            val src = entry.optString("src").takeIf { it.isNotBlank() } ?: continue
            val type = entry.optString("type")
            if (type.isNotBlank() && !type.startsWith("image/")) continue
            val sizes = entry.optString("sizes")
            val side = sizes.split('x', ' ')
                .mapNotNull { it.trim().removeSuffix("px").toIntOrNull() }
                .maxOrNull() ?: 0
            val purpose = entry.optString("purpose")
            // A maskable icon is designed to be cropped, so prefer "any".
            val score = side * if (purpose.contains("maskable")) 1 else 2
            if (best == null || score > best!!.first) {
                val resolved = runCatching { java.net.URI(pageUrl).resolve(src).toString() }.getOrNull()
                if (resolved != null && (resolved.startsWith("https://") || resolved.startsWith("http://"))) {
                    best = score to resolved
                }
            }
        }
        return best?.second
    }

    /**
     * Web app display mode.
     *
     * The chrome is only hidden for the app the user actually installed, that is
     * when the browser was launched from its pinned shortcut. Hiding it merely
     * because a page ships a manifest is a trap: a site that declares
     * `scope: "/"` would strip the toolbar from every page on its origin and the
     * user would be left with no address bar, no tab tray and no way back.
     */
    private fun applyWebAppDisplayMode() {
        if (!::browserControls.isInitialized) return
        val tab = activeTab() ?: return
        val display = webAppManifest?.optString("display").orEmpty().lowercase()
        val launched = webAppOrigin
        val onAppOrigin = launched != null && tab.url.isNotBlank() &&
            runCatching { Uri.parse(tab.url).host == Uri.parse(launched).host }.getOrDefault(false)
        val immersive = onAppOrigin && (display == "fullscreen" || display == "standalone")
        val wasImmersive = immersiveMode
        immersiveMode = immersive
        if (immersive) {
            chromeRevealed = false
            if (fullscreenSession == null) applyChromeVisibility()
        } else if (wasImmersive) {
            chromeRevealed = false
            applyChromeVisibility()
        }
    }

    private fun applyChromeVisibility() {
        val fullscreen = fullscreenSession != null
        val hidden = fullscreen || (immersiveMode && !chromeRevealed)
        browserControls.visibility = if (hidden) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_pip).visibility = if (fullscreen) View.VISIBLE else View.GONE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            setSystemBarsVisible()
        }
    }

    /**
     * In a web app's own display mode the chrome is hidden, so it has to be
     * reachable again: a tap near the top edge brings it back, and a second tap
     * puts it away, the way an installed app behaves. Gecko still receives the
     * touch either way.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::browserControls.isInitialized && immersiveMode && fullscreenSession == null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapDownAt = ev.eventTime
                    tapDownX = ev.rawX
                    tapDownY = ev.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val isTap = ev.eventTime - tapDownAt < 400 &&
                        kotlin.math.abs(ev.rawX - tapDownX) < dp(24) &&
                        kotlin.math.abs(ev.rawY - tapDownY) < dp(24)
                    val topEdge = ev.rawY <= window.decorView.height * 0.15f
                    // A tap brings the chrome back; once it is back, only a tap
                    // near the top edge puts it away again, so ordinary taps on
                    // the page still reach the page.
                    if (isTap && (!chromeRevealed || topEdge)) {
                        chromeRevealed = !chromeRevealed
                        applyChromeVisibility()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showTabsSheet() {
        val inactive = inactiveIds()
        val sheet = TabsSheet().apply {
            tabs = snapshot()
            inactiveTabs = snapshot().filter { it.id in inactive }
            trackersText = this@MainActivity.trayTrackersText
            columns = store.tabViewColumns
            showGroups = store.tabGroups
            // The button now counts one kind of tab, so it opens that kind. A
            // button reading "2" for private tabs that lands on the normal tray
            // would be showing the user a page the number does not describe.
            mode = if (activeTab()?.isPrivate == true) {
                TabsSheet.MODE_PRIVATE
            } else {
                TabsSheet.MODE_TABS
            }
            onSelect = { selectTab(it) }
            onClose = { closeTab(it) }
            onCloseAll = { closeAllTabs(it) }
            onNewTab = { newTab(isPrivate = it, select = true) }
            onTabMenu = { showTabMenu(it) }
        }
        tabsSheet = sheet
        sheet.show(supportFragmentManager, "tabs")
    }

    private fun showTabMenu(item: TabItem) {
        val source = tabs.firstOrNull { it.id == item.id } ?: return
        val index = tabs.indexOfFirst { it.id == item.id }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!source.isHome && source.url.isNotBlank()) {
            actions += getString(R.string.duplicate_tab) to {
                val duplicate = newTab(source.isPrivate, select = true).also { copied ->
                    copied.isHome = false
                    copied.url = source.url
                    copied.title = source.title
                    copied.session.loadUri(source.url)
                }
                updateBadge()
            }
            actions += getString(R.string.share) to { shareUri(source.url) }
        }
        actions += getString(R.string.close_other_tabs) to {
            val victims = tabs.filter { it.id != item.id && it.isPrivate == source.isPrivate }.map { it.id }
            closeTabIds(victims)
        }
        actions += getString(R.string.close_tabs_right) to {
            val victims = tabs.drop(index + 1).filter { it.isPrivate == source.isPrivate }.map { it.id }
            closeTabIds(victims)
        }
        actions += getString(R.string.close) to { closeTab(item.id) }
        AlertDialog.Builder(this)
            .setTitle(item.title.ifBlank { item.url.ifBlank { getString(R.string.new_tab) } })
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun closeTabIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        // Closing several at once is easy to do by accident and impossible to
        // undo, so it asks first. One tab needs no confirmation.
        if (store.confirmCloseTabs && ids.size > 1) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.close_tabs_confirm, ids.size))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.close) { _, _ -> reallyCloseTabIds(ids) }
                .show()
            return
        }
        reallyCloseTabIds(ids)
    }

    private fun reallyCloseTabIds(ids: List<Long>) {
        val survivors = tabs.filter { it.id !in ids }
        if (survivors.isNotEmpty() && activeId in ids) selectTab(survivors.first().id)
        ids.forEach { id -> if (tabs.any { it.id == id }) closeTab(id) }
        saveTabState()
    }

    /** Page actions (long-press the address bar). */
    /**
     * The actions that apply to the page in front, rendered as a horizontal row
     * at the top of the menu sheet.
     *
     * This was a dialog behind a long press on the address bar, which meant the
     * only route to reload, find-in-page or request-desktop was a gesture that
     * also swallowed the platform's own text menu. In the sheet it is reachable
     * without colliding with editing, and it sits above the navigation rows
     * because it acts on the page rather than moving around the app.
     *
     * Add bookmark and Save login are deliberately absent: both are one tap away
     * from the menu's own Bookmarks and Passwords rows, and repeating them here
     * only gave two ways to reach the same screen.
     */
    private fun pageActions(): List<MenuSheet.PageAction> {
        val tab = activeTab() ?: return emptyList()
        // Four fixed tiles, plus Forward only when the engine says there is
        // somewhere to go. The grid takes the tile count as its column count,
        // so both shapes are exactly one full row -- never a ragged half-row.
        // Forward uses the engine's own forward stack (canGoForward from
        // onCanGoForward); there is no second history kept beside it.
        val reload = MenuSheet.PageAction(
            getString(if (tab.isLoading) R.string.stop else R.string.reload),
            if (tab.isLoading) R.drawable.ic_stop else R.drawable.ic_reload,
        ) {
            if (tab.isLoading) tab.session.stop()
            else if (tab.isError) tab.session.loadUri(tab.url)
            else tab.session.reload()
        }
        val home = MenuSheet.PageAction(getString(R.string.homepage), R.drawable.ic_home) { goHome() }
        val back = MenuSheet.PageAction(getString(R.string.back), R.drawable.ic_back) { tab.session.goBack() }
        val share = MenuSheet.PageAction(getString(R.string.share), R.drawable.ic_share) { shareCurrent() }
        return if (tab.canGoForward) {
            listOf(
                reload,
                home,
                back,
                MenuSheet.PageAction(getString(R.string.forward), R.drawable.ic_forward) {
                    tab.session.goForward()
                },
                share,
            )
        } else {
            listOf(reload, home, back, share)
        }
    }

    private fun showFindInPage(tab: Tab) {
        // Highlight every match, not just the current one, so the user can see
        // how much of the page the query hits.
        runCatching {
            tab.session.finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.find_hint)
            setSingleLine(true)
            requestFocus()
        }
        val status = TextView(this).apply {
            setPadding(0, 10, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.librewolf_grey))
            setText(R.string.find_type_to_search)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(status)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.find_in_page)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.find_previous, null)
            .setPositiveButton(R.string.find_next, null)
            .create()
        dialog.show()

        // The default button handlers dismiss the dialog, which made stepping
        // through matches impossible. Replace them so the bar stays open and
        // reports the running count instead.
        fun search(direction: Int) {
            val query = input.text.toString()
            if (query.isBlank()) {
                status.setText(R.string.find_type_to_search)
                return
            }
            tab.session.finder.find(query, direction).accept { result ->
                if (result == null || !result.found) {
                    status.setText(R.string.find_no_matches)
                } else {
                    status.text = getString(R.string.find_matches, result.current, result.total)
                }
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            search(GeckoSession.FINDER_FIND_FORWARD)
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            search(GeckoSession.FINDER_FIND_BACKWARDS)
        }
        dialog.setOnDismissListener {
            runCatching {
                tab.session.finder.clear()
                tab.session.finder.setDisplayFlags(0)
            }
        }

        // Search as the user types, so the count appears without pressing Next.
        input.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    search(GeckoSession.FINDER_FIND_FORWARD)
                }
            },
        )
    }

    private fun shareCurrent() {
        val url = activeTab()?.url.orEmpty()
        if (url.isEmpty()) return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                getString(R.string.share),
            ),
        )
    }

    private fun showPrivacyReport() {
        // Opens the same page Settings shows, rather than a five-line dialog
        // built from a second, independent set of reads. That duplication is how
        // the two drift apart and start disagreeing with each other; there is now
        // one page and one read of the stores.
        startActivity(
            Intent(this@MainActivity, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SCREEN, SettingsScreens.PRIVACY_REPORT),
        )
    }

    private fun askQuit() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.quit_confirm))
            .setPositiveButton(getString(R.string.quit)) { _, _ ->
                // Quitting is the only destroy path that sanitizes. A Back-press
                // exit, a recents dismiss or a configuration recreation all run
                // through onDestroy too, and none of them is the user asking
                // for their data to be wiped.
                quitting = true
                sanitize(keepPage = true)
                finishAffinity()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun sanitize(keepPage: Boolean) {
        eraseEngineData()
        if (!keepPage) goHome() else activeTab()?.session?.reload()
    }

    override fun onDestroy() {
        saveTabState()
        // A reminder that outlives the browser is worse than no reminder: it
        // would claim private browsing is on when the app is gone.
        if (::privateIndicator.isInitialized) privateIndicator.dismiss()
        if (::mediaController.isInitialized) mediaController.release()
        // Sanitizing here is gated on an explicit Quit, not on destruction
        // itself. Before the gate, leaving with Back (or a theme change, which
        // also recreates) wiped tabs, history, cookies and site data on a
        // fresh install, because the desktop-mirrored defaults sanitize
        // history, cookies, site data and cache. Quitting stays the deliberate
        // path; everything else keeps state for the next launch.
        if (quitting && store.sanitizeOnShutdown) {
            // Category by category, because "delete on quit" is a set of
            // separate promises and the user is allowed to keep some of them.
            if (store.sanitizeHistory) {
                sessionStore.clear()
                historyStore.clear()
            }
            if (store.sanitizeSiteData) sitePermissionStore.clear()
            if (store.sanitizeBookmarks) bookmarkStore.clear()
            for (tab in tabs) tab.session.close()
            tabs.clear()
            eraseEngineDataOnQuit(store)
        } else {
            for (tab in tabs) tab.session.close()
            tabs.clear()
        }
        super.onDestroy()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setIcon(R.drawable.librewolf_logo)
            .setMessage(
                "LibreWolf Android ${BuildConfig.VERSION_NAME}\n" +
                    "Upstream Firefox: ${BuildConfig.UPSTREAM_FIREFOX} / LW release ${BuildConfig.LIBREWOLF_RELEASE}\n" +
                    "Engine: GeckoView ${LibreWolfDefaults.GECKOVIEW_VERSION}\n\n" +
                    "Privacy defaults ported from source/settings/librewolf.cfg.\n" +
                    "No telemetry. Updates: ${LibreWolfDefaults.UPDATE_URL}",
            )
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.open_site) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LibreWolfDefaults.HOMEPAGE)))
            }
            .show()
    }
}
