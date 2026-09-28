package com.palash.librewolfandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * A standing reminder that the tab in front is a private one.
 *
 * A private window looks identical to a normal one, which is the whole problem:
 * it is easy to type a real account into a tab that will forget it. The
 * indicator is deliberately low-importance and silent, so it is information
 * rather than an interruption.
 *
 * The user can dismiss it, and it then stays dismissed for as long as private
 * browsing stays on. Reposting it on every resume would make "dismiss" a lie.
 * Turning private mode off and on again brings it back.
 */
class PrivateBrowsingNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    private var posted = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.private_browsing_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.private_browsing_channel_description)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * Brings the indicator in line with the active tab. Safe to call as often as
     * the active tab might have changed; it only posts on an actual transition.
     */
    fun sync(privateActive: Boolean) {
        if (!privateActive) {
            // Leaving private mode clears the dismissal, so the next private tab
            // is reminded about again.
            dismissed = false
            cancel()
            return
        }
        if (dismissed) {
            // The user said they did not want to be reminded. Saying so again on
            // the next resume would make "dismiss" a lie.
            cancel()
            return
        }
        post()
    }

    /**
     * Called when the browser comes forward, which also covers a launch that
     * restored tabs without going through [sync]'s usual path.
     */
    fun onResume(privateActive: Boolean) = sync(privateActive)

    /** Called when the browser is going away, so the reminder cannot outlive it. */
    fun dismiss() {
        dismissed = true
        cancel()
    }

    private fun post() {
        // Check the permission rather than posting and catching the refusal. Not
        // being able to show the reminder is the normal case for a user who said
        // no, and a declined notification should not cost an exception per post.
        // Lint is right to insist: manager.notify() is annotated as requiring
        // POST_NOTIFICATIONS and throws SecurityException without it.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cancel()
            return
        }
        // Both the tap and the swipe go to the same receiver, and neither opens
        // the browser. That is the whole reason this is a receiver and not the
        // usual "tap to open the app" intent: tapping used to bring the browser
        // forward, whose onResume then re-posted the indicator faster than the
        // system could cancel it, so tapping appeared to do nothing at all.
        // Nothing here launches an activity, so nothing can race the dismissal.
        val intent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, PrivateBrowsingDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.private_browsing_notification_title))
            .setContentText(context.getString(R.string.private_browsing_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .setDeleteIntent(intent)
            .setContentIntent(intent)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        // Posting is still guarded, because the permission can be revoked between
        // the check above and this call.
        runCatching { manager.notify(ID, notification) }
            .onSuccess { posted = true }
    }

    private fun cancel() {
        if (!posted) return
        manager.cancel(ID)
        posted = false
    }

    companion object {
        private const val CHANNEL_ID = "private_browsing"
        private const val ID = 4101

        /**
         * Whether the user has dismissed the indicator.
         *
         * Held per process rather than on disk on purpose. A dismissal is a
         * "not right now", and a reminder that survives the browser being killed
         * would be a reminder the user has no way to get rid of. The delete
         * intent cannot reach the activity that owns this object, so the flag
         * lives where the receiver can reach it.
         */
        @Volatile
        @JvmStatic
        var dismissed = false

        /**
         * Records that the user removed the indicator, and removes it.
         *
         * The cancel belongs here rather than in the next [sync] because the
         * order of events is not ours to choose: tapping the indicator brings
         * the browser forward, and the browser re-checks the state on the way
         * back in, which can be before or after this broadcast arrives. Whichever
         * runs last has to leave the same result, and doing the cancel here is
         * what makes that true.
         */
        @JvmStatic
        fun markDismissed(context: Context) {
            dismissed = true
            runCatching { NotificationManagerCompat.from(context).cancel(ID) }
        }
    }
}

/**
 * Receives the indicator's delete intent.
 *
 * Android gives no callback to the app for a notification the user swipes away
 * or taps when it is set to cancel itself, and noticing the absence instead
 * races the system. The delete intent is the only notification that arrives
 * *because* the user acted.
 */
class PrivateBrowsingDismissReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        PrivateBrowsingNotifier.markDismissed(context)
    }
}
