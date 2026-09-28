package com.palash.librewolfandroid

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps Gecko media playback alive while the browser Activity is backgrounded.
 *
 * The notification itself is built by [BrowserMediaController] so the shade shows
 * one media entry with working transport controls rather than a second, inert one.
 */
class BrowserMediaService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = BrowserMediaController.buildNotification(this, BrowserMediaController.mediaToken())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 4102
        private const val ACTION_STOP = "com.palash.librewolfandroid.STOP_MEDIA"

        fun start(context: android.content.Context) {
            val intent = Intent(context, BrowserMediaService::class.java)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, BrowserMediaService::class.java))
        }
    }
}
