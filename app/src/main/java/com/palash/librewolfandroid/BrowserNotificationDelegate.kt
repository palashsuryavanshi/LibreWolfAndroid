package com.palash.librewolfandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

/** Displays standards-based web notifications through Android. */
class BrowserNotificationDelegate(
    private val activity: MainActivity,
) : WebNotificationDelegate {

    private val manager = NotificationManagerCompat.from(activity)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                activity.getString(R.string.web_notifications_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = activity.getString(R.string.web_notifications_channel_description)
            }
            activity.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onShowNotification(notification: WebNotification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val source = notification.source?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val openIntent = Intent(activity, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_NOTIFICATION
            putExtra(MainActivity.EXTRA_NOTIFICATION_URL, source)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            activity,
            notification.tag.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.app_name))
            .setContentText(notification.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        notification.source?.let { builder.setSubText(hostOf(it)) }
        runCatching { manager.notify(notification.tag.hashCode(), builder.build()) }
    }

    override fun onCloseNotification(notification: WebNotification) {
        manager.cancel(notification.tag.hashCode())
    }

    private fun hostOf(uri: String): String = runCatching { android.net.Uri.parse(uri).host }
        .getOrNull()?.removePrefix("www.") ?: ""

    companion object {
        private const val CHANNEL_ID = "website_notifications"
    }
}
