package com.palash.librewolfandroid

import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * Bridges website permission requests to explicit browser prompts and Android
 * runtime permissions. No Android permission is granted merely because a site
 * requested it.
 */
class BrowserPermissionDelegate(
    private val activity: AppCompatActivity,
    private val sitePermissions: SitePermissionStore,
    private val currentOrigin: (GeckoSession?) -> String?,
    private val currentPrivate: (GeckoSession?) -> Boolean,
    private val launchAndroidPermissions: (Array<String>, (Boolean) -> Unit) -> Unit,
) : GeckoSession.PermissionDelegate {

    private data class PendingAndroidRequest(
        val permissions: Array<String>,
        val callback: GeckoSession.PermissionDelegate.Callback,
        val origin: String?,
        val privateMode: Boolean,
    )

    private data class PendingMediaRequest(
        val uri: String,
        val video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        val audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        val callback: GeckoSession.PermissionDelegate.MediaCallback,
    )

    private val androidRequests = ArrayDeque<PendingAndroidRequest>()
    private var activeAndroidRequest: PendingAndroidRequest? = null
    private val pendingContentPermissions = mutableMapOf<String, GeckoResult<Int>>()
    private val mediaPermissionKey = 1002

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<String>?,
        callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        val missing = permissions.orEmpty().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }.distinct().toTypedArray()
        val origin = currentOrigin(session)
        val privateMode = currentPrivate(session)
        val request = PendingAndroidRequest(missing, callback, origin, privateMode)
        val requestsMedia = permissions.orEmpty().any {
            it == android.Manifest.permission.CAMERA || it == android.Manifest.permission.RECORD_AUDIO
        }
        if (requestsMedia && !origin.isNullOrBlank()) {
            // Gecko may ask for Android camera/microphone permission before it
            // emits onMediaPermissionRequest. Ask the site first, then request
            // the OS permission so the website → browser → Android flow is real.
            // Camera and microphone are recorded under separate keys so the
            // per-site screens can show and change them independently; the
            // engine has no permission constant for either, so this store is
            // the only place the answer can live.
            val keys = mutableSetOf<Int>()
            if (missing.any { it == android.Manifest.permission.CAMERA }) keys += SitePermissionIndex.KEY_CAMERA
            if (missing.any { it == android.Manifest.permission.RECORD_AUDIO }) keys += SitePermissionIndex.KEY_MICROPHONE
            if (keys.isEmpty()) keys += SitePermissionIndex.KEY_MEDIA
            requestContentPermission(origin, keys.first(), privateMode).accept { decision ->
                val allowed = decision == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                if (allowed) {
                    // One answer covers every key the request asked for.
                    keys.forEach { key ->
                        sitePermissions.set(origin, key, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    }
                    sitePermissions.set(origin, SitePermissionIndex.KEY_MEDIA, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                } else {
                    keys.forEach { key ->
                        sitePermissions.set(origin, key, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    }
                    sitePermissions.set(origin, SitePermissionIndex.KEY_MEDIA, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                }
                if (allowed) {
                    if (missing.isEmpty()) {
                        callback.grant()
                    } else {
                        androidRequests.addLast(request)
                        dispatchNextAndroidRequest()
                    }
                } else {
                    callback.reject()
                }
            }
        } else if (missing.isEmpty()) {
            finishAndroidRequest(request, granted = true)
        } else {
            androidRequests.addLast(request)
            dispatchNextAndroidRequest()
        }
    }

    private fun dispatchNextAndroidRequest() {
        if (activeAndroidRequest != null) return
        val request = androidRequests.removeFirstOrNull() ?: return
        activeAndroidRequest = request
        launchAndroidPermissions(request.permissions) { granted ->
            val active = activeAndroidRequest
            activeAndroidRequest = null
            if (active != null) finishAndroidRequest(active, granted)
            dispatchNextAndroidRequest()
        }
    }

    private fun finishAndroidRequest(request: PendingAndroidRequest, granted: Boolean) {
        if (!granted) {
            request.callback.reject()
            return
        }
        val origin = request.origin
        val browserPermission = when {
            request.permissions.any {
                it == android.Manifest.permission.ACCESS_FINE_LOCATION ||
                    it == android.Manifest.permission.ACCESS_COARSE_LOCATION
            } -> GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION
            request.permissions.any { it == android.Manifest.permission.POST_NOTIFICATIONS } ->
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION
            else -> null
        }
        if (browserPermission != null && !origin.isNullOrBlank()) {
            requestContentPermission(origin, browserPermission, request.privateMode).accept { value ->
                if (value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) {
                    request.callback.grant()
                } else {
                    request.callback.reject()
                }
            }
        } else {
            request.callback.grant()
        }
    }

    fun ensureAndroidPermissions(
        permissions: Array<String>,
        callback: (Boolean) -> Unit,
    ) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }.distinct().toTypedArray()
        if (missing.isEmpty()) {
            callback(true)
            return
        }
        launchAndroidPermissions(missing, callback)
    }

    private fun permissionName(permission: Int): String = when (permission) {
        GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> activity.getString(R.string.permission_location)
        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> activity.getString(R.string.permission_notifications)
        GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> activity.getString(R.string.permission_storage)
        GeckoSession.PermissionDelegate.PERMISSION_XR -> activity.getString(R.string.permission_device)
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
        -> activity.getString(R.string.permission_autoplay)
        GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> activity.getString(R.string.permission_drm)
        GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> activity.getString(R.string.permission_storage_access)
        mediaPermissionKey -> activity.getString(R.string.permission_media)
        GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
        GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS,
        -> activity.getString(R.string.permission_local_network)
        else -> activity.getString(R.string.permission_website)
    }

    private fun host(uri: String): String = runCatching { android.net.Uri.parse(uri).host }
        .getOrNull()?.removePrefix("www.") ?: activity.getString(R.string.this_website)

    /**
     * The global default for a permission, or null when the user should be asked.
     *
     * Only autoplay and web notifications have a default in Settings → Websites.
     * Everything else stays on the prompt: a default for the camera or for
     * location would mean a switch that silently grants a device capability.
     */
    private fun defaultFor(permission: Int): Int? {
        val store = PrivacyStore(activity)
        val mode = when (permission) {
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
            -> store.autoplayMode
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> store.notificationMode
            else -> return null
        }
        return when (mode) {
            1 -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            2 -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            else -> null
        }
    }

    private fun rememberContentPermission(uri: String, permission: Int, value: Int) {
        if (permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
            permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
        ) {
            sitePermissions.set(uri, GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, value)
            sitePermissions.set(uri, GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE, value)
        } else {
            sitePermissions.set(uri, permission, value)
        }
    }

    private fun permissionKeyFor(uri: String, permission: Int): String = "${host(uri)}|$permission"

    private fun requestNotificationSystemPermission(uri: String, key: String, result: GeckoResult<Int>) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            completeContentPermission(key, result, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            return
        }
        val store = PrivacyStore(activity)
        if (
            ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            completeContentPermission(key, result, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            return
        }
        if (store.notificationPermissionAsked) {
            // Already put this in front of the user once and it is still not
            // granted, so the answer was no. Android may not even show a dialog
            // again after a permanent denial, so re-launching would only produce
            // a silent denial and another in-app prompt with nowhere to go.
            // The site's own stored decision is left alone: if the user later
            // grants notifications from system settings, the site should still
            // get them without being asked a second time.
            completeContentPermission(key, result, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            return
        }
        store.notificationPermissionAsked = true
        ensureAndroidPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) { granted ->
            if (granted) {
                completeContentPermission(key, result, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            } else {
                rememberContentPermission(uri, GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                completeContentPermission(key, result, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
    }

    private fun completeContentPermission(key: String, result: GeckoResult<Int>, value: Int) {
        pendingContentPermissions.remove(key)
        result.complete(value)
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission,
    ): GeckoResult<Int> = requestContentPermission(perm.uri, perm.permission, perm.privateMode)

    private fun requestContentPermission(
        uri: String,
        permission: Int,
        privateMode: Boolean,
    ): GeckoResult<Int> {
        // DRM itself is mediated by Android MediaDrm/Widevine and grants no general
        // device access. Preserve normal streaming compatibility by allowing this
        // browser-level capability.
        if (permission == GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS) {
            rememberContentPermission(uri, permission, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
        }
        val rememberedPermission = if (
            permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
            permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
        ) {
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE
        } else {
            permission
        }
        (sitePermissions.get(uri, rememberedPermission)
            ?: sitePermissions.get(uri, GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE))
            ?.let { remembered ->
                if (permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION &&
                    remembered == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                ) {
                    val result = GeckoResult<Int>()
                    requestNotificationSystemPermission(uri, permissionKeyFor(uri, rememberedPermission), result)
                    return result
                }
                return GeckoResult.fromValue(remembered)
            }
        // No decision for this site yet, so the global default from
        // Settings → Websites applies. Ask is the default, which is why a site
        // still gets a prompt on a fresh install.
        defaultFor(permission)?.let { default ->
            if (permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION &&
                default == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            ) {
                val result = GeckoResult<Int>()
                requestNotificationSystemPermission(uri, permissionKeyFor(uri, rememberedPermission), result)
                return result
            }
            return GeckoResult.fromValue(default)
        }
        // Never let content disable tracking protection or bypass the app's local
        // network policy through a generic permission prompt.
        if (permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING ||
            permission == GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS ||
            permission == GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS
        ) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }
        if (activity.isFinishing || activity.isDestroyed || privateMode) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }
        val permissionKey = permissionKeyFor(uri, rememberedPermission)
        pendingContentPermissions[permissionKey]?.let { return it }

        val result = GeckoResult<Int>()
        pendingContentPermissions[permissionKey] = result
        val site = host(uri)
        var completed = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle(permissionName(permission))
            .setMessage(activity.getString(R.string.permission_site_message, site))
            .setNegativeButton(R.string.deny) { _, _ ->
                completed = true
                rememberContentPermission(uri, permission, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                completeContentPermission(permissionKey, result, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            .setPositiveButton(R.string.allow) { _, _ ->
                completed = true
                rememberContentPermission(uri, permission, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                if (permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
                    requestNotificationSystemPermission(uri, permissionKey, result)
                } else {
                    completeContentPermission(permissionKey, result, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                }
            }
            .create()
        dialog.setOnDismissListener {
            if (!completed) {
                completed = true
                rememberContentPermission(uri, permission, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                completeContentPermission(permissionKey, result, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
        dialog.show()
        return result
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        val required = buildList {
            if (!video.isNullOrEmpty()) add(Manifest.permission.CAMERA)
            if (!audio.isNullOrEmpty()) add(Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()
        if (required.isEmpty()) {
            callback.reject()
            return
        }
        val remembered = sitePermissions.get(uri, mediaPermissionKey)
        if (remembered == GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) {
            callback.reject()
            return
        }
        val request = PendingMediaRequest(uri, video, audio, callback)
        if (remembered == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) {
            grantMediaAfterAndroidPermission(request, required)
        } else {
            requestContentPermission(uri, mediaPermissionKey, currentPrivate(session)).accept { decision ->
                if (decision == GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) {
                    callback.reject()
                } else {
                    grantMediaAfterAndroidPermission(request, required)
                }
            }
        }
    }

    private fun grantMediaAfterAndroidPermission(
        request: PendingMediaRequest,
        required: Array<String>,
    ) {
        ensureAndroidPermissions(required) { granted ->
            if (!granted) {
                request.callback.reject()
            } else {
                request.callback.grant(request.video?.firstOrNull(), request.audio?.firstOrNull())
            }
        }
    }
}
