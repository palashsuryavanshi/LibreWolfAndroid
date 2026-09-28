package com.palash.librewolfandroid

import android.content.Context
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.StorageController

/**
 * Per-site website permissions, read from both stores and writable.
 *
 * Two stores hold these decisions and they are not interchangeable:
 *
 * - The **engine** keeps one for location, notifications, autoplay and storage
 *   through `StorageController`, and consults it *before* asking the embedder.
 *   Its entries are the ones it will act on without us.
 * - The **shell's** [SitePermissionStore] holds what the user answered in the
 *   browser's own prompt, and is what [BrowserPermissionDelegate] checks first
 *   when the engine does ask.
 *
 * GeckoView 147 exposes no `PERMISSION_CAMERA` or `PERMISSION_MICROPHONE`
 * constant — a camera request arrives as a media source, not a content
 * permission — so the camera and microphone decisions exist only in the shell's
 * store. That is not a workaround; it is the only place they can exist, and
 * since the engine never keeps a copy it always asks.
 *
 * Writing therefore always updates the shell's store, and additionally pushes
 * the decision into the engine where the engine has a slot for it. Where the
 * push is refused the engine's stale copy is dropped instead, which makes it ask
 * again and take the answer from here.
 */
object SitePermissionIndex {

    // The shell's own keys for capabilities the engine does not model.
    const val KEY_CAMERA = 1003
    const val KEY_MICROPHONE = 1004
    const val KEY_MEDIA = 1002

    data class Entry(val authority: String, val permission: Int, val value: Int, val fromEngine: Boolean)

    /** Every decision the browser knows about, engine and shell merged. */
    fun entries(context: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        val engine = enginePermissions(context)
        val shell = SitePermissionStore(context).all()
        // The shell's answer is authoritative: it is what the user picked last,
        // and it is what the delegate returns.
        val keys = (engine.keys + shell.keys).toSet()
        for (authority in keys) {
            val perms = mutableMapOf<Int, Int>()
            engine[authority]?.let { perms.putAll(it) }
            shell[authority]?.let { perms.putAll(it) }
            for ((permission, value) in perms) {
                out += Entry(authority, permission, value, fromEngine = engine[authority]?.containsKey(permission) == true)
            }
        }
        return out.sortedWith(compareBy({ it.authority }, { it.permission }))
    }

    /** The engine's own copy, keyed `host:port` so it lines up with the shell. */
    private fun enginePermissions(context: Context): Map<String, Map<Int, Int>> {
        val result = mutableMapOf<String, MutableMap<Int, Int>>()
        val controller = MainActivity.runtimeRef()?.storageController ?: return result
        controller.getAllPermissions().accept(
            { permissions ->
                permissions.orEmpty().forEach { permission ->
                    val authority = authorityOf(permission.uri) ?: return@forEach
                    result.getOrPut(authority) { mutableMapOf() }[normalise(permission.permission)] = permission.value
                }
            },
            { },
        )
        return result
    }

    /**
     * `host:port`, matching the key the shell's store uses.
     *
     * GeckoView hands out a full origin and `Uri.host` drops the port, so a
     * naive read lists one site's decision twice and a reset leaves half of it
     * behind.
     */
    fun authorityOf(uri: String?): String? {
        val parsed = runCatching { android.net.Uri.parse(uri.orEmpty()) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = if (parsed.scheme.equals("https", ignoreCase = true)) 443 else 80
        val port = parsed.port
        return if (port != -1 && port != defaultPort) "$host:$port" else host
    }

    private fun normalise(permission: Int): Int = when (permission) {
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE
        else -> permission
    }

    /**
     * Records a decision for one site.
     *
     * Returns true when the engine's copy was updated as well. A false return
     * means the shell's store holds the answer and the engine's stale entry was
     * cleared, which is the state the delegate expects.
     */
    fun set(context: Context, authority: String, permission: Int, value: Int): Boolean {
        SitePermissionStore(context).set("https://$authority", normalise(permission), value)
        val controller = MainActivity.runtimeRef()?.storageController ?: return false
        val pushed = runCatching {
            val json = JSONObject()
                .put("uri", "https://$authority")
                .put("permission", normalise(permission))
                .put("value", value)
                .put("privateMode", false)
            val content = ContentPermission.fromJson(json) ?: return false
            controller.setPermission(content, value)
            true
        }.getOrDefault(false)
        if (!pushed) {
            // The engine still holds a decision it will act on without asking.
            // Dropping it makes it ask, and the delegate answers from the store
            // written above.
            val host = authority.substringBefore(':')
            controller.clearDataFromHost(host, StorageController.ClearFlags.PERMISSIONS)
                .accept({ }, { })
            if (host != authority) {
                controller.clearDataFromHost(authority, StorageController.ClearFlags.PERMISSIONS)
                    .accept({ }, { })
            }
        }
        return pushed
    }

    /** Forgets one site entirely, in both stores. */
    fun clearOrigin(context: Context, authority: String) {
        SitePermissionStore(context).clearOrigin(authority)
        val controller = MainActivity.runtimeRef()?.storageController ?: return
        val host = authority.substringBefore(':')
        controller.clearDataFromHost(host, StorageController.ClearFlags.PERMISSIONS).accept({ }, { })
        if (host != authority) {
            controller.clearDataFromHost(authority, StorageController.ClearFlags.PERMISSIONS).accept({ }, { })
        }
    }

    fun clearAll(context: Context) {
        SitePermissionStore(context).clear()
        MainActivity.eraseEngineData(StorageController.ClearFlags.PERMISSIONS)
    }

    /**
     * The capability's name as a settings row reads.
     *
     * These are noun phrases. The same permissions carry a question form in the
     * prompt the user answers ("Share your location?"), and reusing that here
     * produced a list of questions instead of a list of capabilities.
     */
    fun label(context: Context, permission: Int): String = when (permission) {
        GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> context.getString(R.string.cap_location)
        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> context.getString(R.string.cap_notifications)
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> context.getString(R.string.cap_autoplay)
        GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> context.getString(R.string.cap_storage)
        GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> context.getString(R.string.cap_storage_access)
        GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> context.getString(R.string.cap_drm)
        GeckoSession.PermissionDelegate.PERMISSION_XR -> context.getString(R.string.cap_device)
        KEY_CAMERA -> context.getString(R.string.permission_camera)
        KEY_MICROPHONE -> context.getString(R.string.permission_microphone)
        KEY_MEDIA -> context.getString(R.string.permission_media)
        else -> context.getString(R.string.cap_website)
    }

    fun valueLabel(context: Context, value: Int): String = when (value) {
        ContentPermission.VALUE_ALLOW -> context.getString(R.string.allowed)
        ContentPermission.VALUE_DENY -> context.getString(R.string.deny)
        else -> context.getString(R.string.ask_every_time)
    }

    /** The permissions worth a page of their own, in the order they are shown. */
    fun capabilities(): IntArray = intArrayOf(
        GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION,
        KEY_CAMERA,
        KEY_MICROPHONE,
        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION,
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
        KEY_MEDIA,
        GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
        GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
    )
}
