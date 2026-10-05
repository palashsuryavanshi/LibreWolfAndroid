package com.palash.librewolfandroid

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app download engine with real pause/resume.
 *
 * android.app.DownloadManager exposes no portable pause/resume API, so this
 * engine owns the transfer: it writes directly to the destination and persists
 * the byte offset, then continues with an HTTP `Range` request. If a server
 * ignores the range it transparently restarts the transfer instead of corrupting
 * the file.
 */
object DownloadEngine {

    private const val BUFFER = 64 * 1024
    private const val MAX_PARALLEL = 3

    /** Minimum gap between persisted progress updates. */
    private const val CHECKPOINT_MS = 500L

    private lateinit var app: Application
    private lateinit var store: DownloadStore
    private val executor = Executors.newFixedThreadPool(MAX_PARALLEL) { r ->
        Thread(r, "lw-download").apply { isDaemon = true }
    }
    private val running = ConcurrentHashMap<String, Handle>()
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Lets pause()/cancel() reach the live socket or stream of a transfer. */
    private class Handle {
        val stop = AtomicBoolean(false)
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var stream: InputStream? = null
    }

    /**
     * Re-requests a URL through GeckoView and hands back a fresh, authenticated
     * body. GeckoView applies the session cookie for us because it owns the
     * cookie jar; the app cannot read that jar. Supplied by the Activity.
     */
    private var engineRefresher: ((url: String, onBody: (InputStream?, Long) -> Unit) -> Unit)? = null

    fun setEngineRefresher(refresher: (url: String, onBody: (InputStream?, Long) -> Unit) -> Unit) {
        engineRefresher = refresher
    }

    fun init(application: Application) {
        app = application
        store = DownloadStore(application)
        // Any task left "RUNNING" by a process death is resumable, not running.
        for (task in store.all()) {
            if (task.state == DownloadState.RUNNING) {
                store.update(task.id) { it.state = DownloadState.PAUSED }
            }
        }
    }

    fun enqueue(
        url: String,
        name: String,
        mime: String,
        folder: String,
        folderUri: String = "",
        headers: Map<String, String> = emptyMap(),
        userAgent: String = "",
    ): DownloadTask {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            name = name,
            mime = mime,
            folder = folder,
            folderUri = folderUri,
            headers = headers,
            createdAt = System.currentTimeMillis(),
            userAgent = userAgent,
        )
        store.upsert(task)
        start(task)
        return task
    }

    /**
     * Starts a download from a body GeckoView has already fetched.
     *
     * This is the path every real download takes. Gecko performs the request
     * with the session cookie attached and hands us the stream, so files behind
     * a sign-in work. Re-requesting the URL ourselves would silently return the
     * login page instead, because GeckoView 147 exposes no cookie API.
     */
    fun enqueueFromEngine(
        url: String,
        name: String,
        mime: String,
        folder: String,
        folderUri: String = "",
        headers: Map<String, String> = emptyMap(),
        body: InputStream?,
        totalBytes: Long = -1L,
    ): DownloadTask {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            name = name,
            mime = mime,
            folder = folder,
            folderUri = folderUri,
            headers = headers,
            createdAt = System.currentTimeMillis(),
            totalBytes = totalBytes,
            viaEngine = true,
        )
        store.upsert(task)
        if (body == null) {
            fail(task.id, "no response body")
            return task
        }
        startStream(task.id, body, skip = 0L)
        return task
    }

    fun pause(id: String) {
        running[id]?.stop?.set(true)
        running[id]?.connection?.disconnect()
        runCatching { running[id]?.stream?.close() }
        store.update(id) { if (!it.state.isFinished) it.state = DownloadState.PAUSED }
        active.remove(id)
        refreshService()
    }

    fun resume(id: String) {
        val task = store.get(id) ?: return
        if (task.state == DownloadState.COMPLETED) return
        if (task.state == DownloadState.CANCELED) {
            retry(id)
            return
        }
        store.update(id) { it.state = DownloadState.QUEUED; it.error = "" }
        val refreshed = store.get(id) ?: return
        if (refreshed.viaEngine) {
            resumeThroughEngine(refreshed)
        } else {
            start(refreshed)
        }
    }

    /**
     * Resumes a Gecko-sourced download. The engine cannot re-issue the request
     * with the session cookie, so Gecko fetches it again and we skip the bytes we
     * already hold before appending the rest.
     */
    private fun resumeThroughEngine(task: DownloadTask) {
        val refresher = engineRefresher
        if (refresher == null) {
            fail(task.id, "cannot resume without the browser")
            return
        }
        store.update(task.id) { it.state = DownloadState.QUEUED }
        refreshService()
        val offset = currentSize(store.get(task.id) ?: task)
        active.add(task.id)
        refresher(task.url) { body, _total ->
            if (body == null) {
                fail(task.id, "could not re-open the file")
                active.remove(task.id)
                refreshService()
            } else {
                startStream(task.id, body, offset)
            }
        }
    }

    fun cancel(id: String) {
        running[id]?.stop?.set(true)
        running[id]?.connection?.disconnect()
        runCatching { running[id]?.stream?.close() }
        store.update(id) { it.state = DownloadState.CANCELED }
        active.remove(id)
        deleteDestination(store.get(id))
        refreshService()
    }

    fun retry(id: String) {
        val task = store.get(id) ?: return
        deleteDestination(task)
        store.update(id) {
            it.state = DownloadState.QUEUED
            it.bytesDownloaded = 0
            it.totalBytes = -1
            it.localRef = ""
            it.error = ""
            it.supportsRange = true
        }
        val refreshed = store.get(id) ?: return
        if (refreshed.viaEngine) {
            resumeThroughEngine(refreshed)
        } else {
            start(refreshed)
        }
    }

    fun remove(id: String) {
        pause(id)
        deleteDestination(store.get(id))
        store.remove(id)
        refreshService()
    }
    fun hasActive(): Boolean = store.all().any { it.state.isActive }

    // ---- transfer ----

    private fun start(task: DownloadTask) {
        if (task.state.isFinished) return
        active.add(task.id)
        store.update(task.id) { it.state = DownloadState.QUEUED }
        refreshService()
        executor.execute { transfer(task.id) }
    }

    /**
     * Copies an already-open response body to the destination. [skip] discards the
     * bytes already on disk so a resumed transfer never duplicates data.
     */
    private fun startStream(id: String, body: InputStream, skip: Long) {
        val handle = Handle()
        handle.stream = body
        running[id] = handle
        active.add(id)
        executor.execute { copyStream(id, body, skip) }
    }

    private fun copyStream(id: String, body: InputStream, skip: Long) {
        val task = store.get(id) ?: return
        val handle = running[id]
        val stop = handle?.stop ?: AtomicBoolean(false)
        try {
            var toSkip = skip
            val skipBuffer = ByteArray(BUFFER)
            while (toSkip > 0) {
                if (stop.get()) return
                val read = body.read(skipBuffer, 0, minOf(BUFFER.toInt(), toSkip.toInt()))
                if (read == -1) break
                toSkip -= read
            }
            val onDisk = currentSize(task)
            if (onDisk != task.bytesDownloaded) {
                store.update(id) { it.bytesDownloaded = onDisk }
            }
            val target = ensureDestination(id, task)
            if (target == null) {
                fail(id, "cannot open destination")
                return
            }
            store.update(id) {
                it.state = DownloadState.RUNNING
                it.bytesDownloaded = onDisk
            }
            openOutput(target, append = onDisk > 0).use { out ->
                val buffer = ByteArray(BUFFER)
                var written = 0L
                var lastCheckpoint = 0L
                while (true) {
                    if (stop.get()) {
                        store.update(id) { it.bytesDownloaded = it.bytesDownloaded + written }
                        return
                    }
                    val read = body.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (now - lastCheckpoint >= CHECKPOINT_MS) {
                        lastCheckpoint = now
                        store.update(id) { it.bytesDownloaded = it.bytesDownloaded + written; written = 0L }
                        publishProgress()
                    }
                }
                out.flush()
                store.update(id) { it.bytesDownloaded = it.bytesDownloaded + written }
            }
            finish(id)
        } catch (e: Exception) {
            if (stop.get()) return
            fail(id, e.message ?: e.javaClass.simpleName)
        } finally {
            running.remove(id)
            active.remove(id)
            runCatching { body.close() }
            refreshService()
        }
    }

    private fun transfer(id: String) {
        val task = store.get(id) ?: return
        val handle = Handle()
        running[id] = handle
        val stop = handle.stop
        var connection: HttpURLConnection? = null
        try {
            connection = URL(task.url).openConnection() as HttpURLConnection
            handle.connection = connection
            val resumeFrom = if (task.supportsRange) currentSize(task) else 0L
            connection.apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                task.userAgent.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("User-Agent", it) }
                task.headers.forEach { (k, v) ->
                    if (k.isNotBlank() && v.isNotBlank()) setRequestProperty(k, v)
                }
                if (resumeFrom > 0) {
                    // The file on disk is the source of truth for the resume
                    // offset. The persisted counter can lag the file (the process
                    // may die between checkpoints), and resuming from it would
                    // re-append bytes the file already has, corrupting the file.
                    store.update(id) { it.bytesDownloaded = resumeFrom }
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                }
            }
            connection.connect()

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0) {
                // Resumed: append.
            } else if (code == HttpURLConnection.HTTP_OK && resumeFrom > 0) {
                // Server ignored the range: restart cleanly instead of corrupting.
                store.update(id) { it.bytesDownloaded = 0; it.localRef = "" }
                deleteDestination(task)
            } else if (code == 416) {
                finish(id)
                return
            } else if (code !in 200..299) {
                fail(id, "HTTP $code")
                return
            }

            val totalHeader = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val already = if (code == HttpURLConnection.HTTP_PARTIAL) resumeFrom else 0L
            val total = if (totalHeader >= 0) totalHeader + already else -1L
            store.update(id) {
                it.state = DownloadState.RUNNING
                it.totalBytes = total
                it.supportsRange = connection.getHeaderField("Accept-Ranges")
                    ?.contains("bytes", true) != false
            }

            val target = ensureDestination(id, task)
            if (target == null) {
                fail(id, "cannot open destination")
                return
            }
            openOutput(target, append = already > 0).use { out ->
                val input: InputStream = connection.inputStream
                val buffer = ByteArray(BUFFER)
                var written = 0L
                // Persist progress and repaint the notification on a time budget,
                // not per buffer: at 64 KB buffers a 250 MB file would otherwise
                // cause thousands of SharedPreferences writes and as many
                // startForegroundService() binder calls, starving input dispatch.
                var lastCheckpoint = 0L
                while (true) {
                    if (stop.get()) {
                        store.update(id) { it.bytesDownloaded = it.bytesDownloaded + written }
                        return
                    }
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (now - lastCheckpoint >= CHECKPOINT_MS) {
                        lastCheckpoint = now
                        store.update(id) { it.bytesDownloaded = it.bytesDownloaded + written; written = 0L }
                        publishProgress()
                    }
                }
                out.flush()
                store.update(id) { it.bytesDownloaded = it.bytesDownloaded + written }
            }
            finish(id)
        } catch (e: Exception) {
            if (stop.get()) return
            fail(id, e.message ?: e.javaClass.simpleName)
        } finally {
            running.remove(id)
            active.remove(id)
            runCatching { connection?.disconnect() }
            refreshService()
        }
    }

    private fun finish(id: String) {
        val task = store.get(id) ?: return
        publishDestination(task)
        store.update(id) { it.state = DownloadState.COMPLETED; it.error = "" }
    }

    private fun fail(id: String, message: String) {
        store.update(id) { it.state = DownloadState.FAILED; it.error = message }
    }

    // ---- destinations ----

    private class Target(val uri: Uri?, val file: File?, val append: Boolean)

    /**
     * Bytes actually present in the destination right now. Used as the resume
     * offset so a resumed transfer can never duplicate or skip data, regardless
     * of how stale the persisted progress counter is.
     */
    private fun currentSize(task: DownloadTask): Long {
        val ref = task.localRef
        if (ref.isEmpty()) return 0L
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ref.startsWith("content://")) {
                app.contentResolver.openAssetFileDescriptor(Uri.parse(ref), "r")
                    ?.use { it.length } ?: 0L
            } else {
                val f = File(ref)
                if (f.exists()) f.length() else 0L
            }
        }.getOrDefault(0L)
    }

    private fun ensureDestination(id: String, task: DownloadTask): Target? {
        val existing = task.localRef
        if (existing.isNotEmpty()) {
            if (existing.startsWith("content://")) {
                return Target(Uri.parse(existing), null, true)
            }
            val f = File(existing)
            if (f.exists()) return Target(null, f, true)
        }
        // A folder picked with the system Files picker wins: the tree URI is
        // where the file goes, and `folder` is only its display name.
        if (task.folderUri.isNotBlank()) {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                app, Uri.parse(task.folderUri),
            ) ?: return null
            val file = tree.createFile(
                task.mime.ifBlank { "application/octet-stream" },
                task.name,
            ) ?: return null
            store.update(id) { it.localRef = file.uri.toString() }
            return Target(file.uri, null, false)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, task.name)
                put(MediaStore.Downloads.MIME_TYPE, task.mime.ifBlank { "application/octet-stream" })
                val rel = buildString {
                    append(Environment.DIRECTORY_DOWNLOADS)
                    if (task.folder.isNotBlank()) append("/").append(task.folder)
                }
                put(MediaStore.Downloads.RELATIVE_PATH, rel)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            store.update(id) { it.localRef = uri.toString() }
            Target(uri, null, false)
        } else {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = if (task.folder.isBlank()) base else File(base, task.folder)
            if (!dir.exists() && !dir.mkdirs()) return null
            val f = File(dir, task.name)
            store.update(id) { it.localRef = f.absolutePath }
            Target(null, f, false)
        }
    }

    private fun openOutput(target: Target, append: Boolean): OutputStream {
        val uri = target.uri
        return if (uri != null) {
            app.contentResolver.openOutputStream(uri, if (append) "wa" else "w")
                ?: throw IllegalStateException("cannot open $uri")
        } else {
            val f = target.file ?: throw IllegalStateException("no target")
            FileOutputStream(f, append)
        }
    }

    private fun publishDestination(task: DownloadTask) {
        val ref = task.localRef
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ref.startsWith("content://")) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                app.contentResolver.update(Uri.parse(ref), values, null, null)
            }
        }
    }

    private fun deleteDestination(task: DownloadTask?) {
        val ref = task?.localRef.orEmpty()
        if (ref.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ref.startsWith("content://")) {
            runCatching { app.contentResolver.delete(Uri.parse(ref), null, null) }
        } else {
            runCatching { File(ref).delete() }
        }
    }

    fun openIntent(context: Context, id: String): Intent? {
        val task = store.get(id) ?: return null
        val ref = task.localRef
        if (ref.isEmpty()) return null
        val uri = if (ref.startsWith("content://")) {
            Uri.parse(ref)
        } else {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                File(ref),
            )
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, task.mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun refreshService() {
        val ctx = app.applicationContext
        val intent = Intent(ctx, DownloadService::class.java)
        if (hasActive()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
        } else {
            runCatching { ctx.stopService(intent) }
            // Nothing is transferring, so the foreground service must go. Paused
            // downloads still need a way back, so leave an ordinary (non-ongoing)
            // notification with Resume/Cancel in their place.
            DownloadService.postIdle(ctx)
        }
    }

    /**
     * Repaints the already-running foreground notification. This must stay cheap:
     * it is called from transfer threads, so the service is never restarted here
     * (a startForegroundService() per progress tick floods the system binder and
     * makes the app unresponsive to touch input).
     */
    private fun publishProgress() {
        if (!::app.isInitialized) return
        DownloadService.postProgress(app.applicationContext)
    }
}
