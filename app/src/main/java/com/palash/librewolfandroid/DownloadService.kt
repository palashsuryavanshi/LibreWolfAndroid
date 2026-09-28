package com.palash.librewolfandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps active downloads alive in the foreground and exposes the
 * pause / resume / cancel actions in the notification shade.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 4711
        const val ACTION_PAUSE = "com.palash.librewolfandroid.DOWNLOAD_PAUSE"
        const val ACTION_RESUME = "com.palash.librewolfandroid.DOWNLOAD_RESUME"
        const val ACTION_CANCEL = "com.palash.librewolfandroid.DOWNLOAD_CANCEL"
        const val EXTRA_ID = "id"

        /** Progress repaints are throttled; the byte counter moves far faster. */
        private const val PROGRESS_THROTTLE_MS = 750L
        private var lastProgressAt = 0L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Download progress and controls"
                    setShowBadge(false)
                },
            )
        }

        /**
         * Repaints the running foreground notification in place. Safe to call from
         * a transfer thread: it never starts a service and is rate limited so a
         * fast transfer cannot flood the notification manager.
         */
        fun postProgress(context: Context) {
            val now = System.currentTimeMillis()
            if (now - lastProgressAt < PROGRESS_THROTTLE_MS) return
            lastProgressAt = now
            val tasks = DownloadStore(context).all().filter { it.state.isActive }
            if (tasks.isEmpty()) return
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, build(context, tasks, ongoing = true))
            }
        }

        /**
         * Notification shown when no transfer is running. Paused downloads stay
         * resumable from the shade; if there are none, the notification is removed.
         */
        fun postIdle(context: Context) {
            val paused = DownloadStore(context).all().filter { it.state == DownloadState.PAUSED }
            val manager = context.getSystemService(NotificationManager::class.java)
            runCatching {
                if (paused.isEmpty()) {
                    manager.cancel(NOTIFICATION_ID)
                } else {
                    manager.notify(NOTIFICATION_ID, build(context, paused, ongoing = false))
                }
            }
        }

        private fun build(context: Context, tasks: List<DownloadTask>, ongoing: Boolean): Notification {
            val primary = tasks.first()
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, DownloadsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_dl)
                .setContentTitle(primary.name)
                .setContentText(statusText(context, primary))
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
            if (primary.totalBytes > 0) {
                builder.setProgress(100, primary.percent, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            if (tasks.size > 1) {
                builder.setSubText("+${tasks.size - 1} more")
            }
            if (primary.state == DownloadState.PAUSED) {
                builder.addAction(0, context.getString(R.string.resume_download), action(context, ACTION_RESUME, primary.id))
                builder.addAction(0, context.getString(R.string.cancel_download), action(context, ACTION_CANCEL, primary.id))
            } else {
                builder.addAction(0, context.getString(R.string.pause_download), action(context, ACTION_PAUSE, primary.id))
                builder.addAction(0, context.getString(R.string.cancel_download), action(context, ACTION_CANCEL, primary.id))
            }
            return builder.build()
        }

        private fun statusText(context: Context, task: DownloadTask): String = when (task.state) {
            DownloadState.PAUSED -> context.getString(R.string.download_paused)
            DownloadState.QUEUED -> context.getString(R.string.download_queued)
            else -> if (task.totalBytes > 0) {
                "${Format.percent(task.bytesDownloaded, task.totalBytes)} • " +
                    "${Format.bytes(context, task.bytesDownloaded)} / ${Format.bytes(context, task.totalBytes)}"
            } else {
                Format.bytes(context, task.bytesDownloaded)
            }
        }

        private fun action(context: Context, action: String, id: String): PendingIntent =
            PendingIntent.getService(
                context,
                (action + id).hashCode(),
                Intent(context, DownloadService::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_ID, id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { DownloadEngine.pause(it) }
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { DownloadEngine.resume(it) }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { DownloadEngine.cancel(it) }
        }
        publish()
        return START_STICKY
    }

    private fun publish() {
        val tasks = DownloadStore(this).all().filter { it.state.isActive }
        if (tasks.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
            postIdle(this)
            return
        }
        startForegroundCompat(build(this, tasks, ongoing = true))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }
}
