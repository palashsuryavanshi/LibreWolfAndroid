package com.palash.librewolfandroid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession as GeckoMediaSession

/**
 * Bridges Gecko media state to the Android media session, the media notification
 * and the audio subsystem.
 *
 * Audio focus, becoming-noisy and output-device changes are handled here so a
 * page cannot keep playing over a call, a navigation prompt or after headphones
 * are unplugged. Routing itself is left to the platform: audio already goes to a
 * connected Bluetooth headset or wired device, so the app never touches a
 * Bluetooth API and never asks for a Bluetooth permission.
 */
class BrowserMediaController(private val activity: MainActivity) {

    private var platformSession: MediaSession? = null
    private var geckoSession: GeckoMediaSession? = null
    private val audioManager: AudioManager = activity.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var title = ""
    private var artist = ""
    private var artwork: Bitmap? = null
    private var focusRequest: AudioFocusRequest? = null
    private var state = PlaybackState.STATE_NONE
    private var positionMs = 0L
    private var durationMs = 0L
    private var rate = 0.0
    private var videoAspect = 16.0 / 9.0
    private var resumeOnFocusGain = false
    private var outputDevice: AudioDeviceInfo? = null

    init {
        attach(this)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                geckoSession?.pause()
            }
            // No volume API is exposed by GeckoView, so a transient duck request is
            // honoured by pausing and restoring on the way back.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                if (state == PlaybackState.STATE_PLAYING) {
                    resumeOnFocusGain = true
                    geckoSession?.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    geckoSession?.play()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                geckoSession?.pause()
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val lost = removedDevices.any { it.id == outputDevice?.id }
            refreshOutputDevice()
            if (lost && state == PlaybackState.STATE_PLAYING) geckoSession?.pause()
        }

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputDevice()
        }
    }

    val delegate = object : GeckoMediaSession.Delegate {
        override fun onActivated(session: GeckoSession, media: GeckoMediaSession) {
            geckoSession = media
            state = PlaybackState.STATE_PAUSED
            platformSession = MediaSession(activity, "LibreWolfMedia").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { geckoSession?.play() }
                    override fun onPause() { geckoSession?.pause() }
                    override fun onStop() { geckoSession?.stop() }
                    override fun onSeekTo(pos: Long) { geckoSession?.seekTo(pos / 1000.0, true) }
                    override fun onSkipToNext() { geckoSession?.nextTrack() }
                    override fun onSkipToPrevious() { geckoSession?.previousTrack() }
                })
                isActive = true
            }
            setToken(platformSession?.sessionToken)
            requestAudioFocus()
            registerNoisyReceiver()
            audioManager.registerAudioDeviceCallback(deviceCallback, main)
            refreshOutputDevice()
            BrowserMediaService.start(activity)
            publish()
        }

        override fun onDeactivated(session: GeckoSession, media: GeckoMediaSession) {
            release()
        }

        override fun onMetadata(
            session: GeckoSession,
            media: GeckoMediaSession,
            metadata: GeckoMediaSession.Metadata,
        ) {
            title = metadata.title.orEmpty()
            artist = metadata.artist.orEmpty()
            metadata.artwork?.getBitmap(ARTWORK_SIZE)?.accept(
                { bitmap -> artwork = bitmap; publish() },
                { },
            )
            publish()
        }

        override fun onPlay(session: GeckoSession, media: GeckoMediaSession) {
            state = PlaybackState.STATE_PLAYING
            publish()
            startTicker()
        }

        override fun onPause(session: GeckoSession, media: GeckoMediaSession) {
            state = PlaybackState.STATE_PAUSED
            stopTicker()
            publish()
        }

        override fun onStop(session: GeckoSession, media: GeckoMediaSession) {
            state = PlaybackState.STATE_STOPPED
            stopTicker()
            publish()
        }

        override fun onPositionState(
            session: GeckoSession,
            media: GeckoMediaSession,
            positionState: GeckoMediaSession.PositionState,
        ) {
            positionMs = (positionState.position * 1000).toLong().coerceAtLeast(0)
            durationMs = (positionState.duration * 1000).toLong().coerceAtLeast(0)
            rate = positionState.playbackRate
            publishState()
        }

        override fun onFullscreen(
            session: GeckoSession,
            media: GeckoMediaSession,
            isFullscreen: Boolean,
            elementMetadata: GeckoMediaSession.ElementMetadata?,
        ) {
            if (elementMetadata != null && elementMetadata.width > 0 && elementMetadata.height > 0) {
                videoAspect = elementMetadata.width.toDouble() / elementMetadata.height.toDouble()
            }
            if (tabForMedia(session) == activity.activeTabId()) {
                activity.setBrowserFullscreen(session, isFullscreen)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (state != PlaybackState.STATE_PLAYING) return
            positionMs += 1000
            publishState()
            main.postDelayed(this, 1_000)
        }
    }

    fun aspectRatio(): Double = videoAspect

    fun release() {
        stopTicker()
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { activity.unregisterReceiver(noisyReceiver) }
        abandonAudioFocus()
        BrowserMediaService.stop(activity)
        platformSession?.isActive = false
        platformSession?.release()
        platformSession = null
        setToken(null)
        geckoSession = null
        state = PlaybackState.STATE_NONE
        snapshot = Snapshot()
        publish()
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1_000)
    }

    private fun stopTicker() {
        main.removeCallbacks(ticker)
    }

    private fun requestAudioFocus() {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener, main)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) geckoSession?.pause()
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun registerNoisyReceiver() {
        runCatching {
            ContextCompat.registerReceiver(
                activity,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Tracks the output the platform is actually using, for display and hot-plug. */
    private fun refreshOutputDevice() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        publish()
    }

    private fun publish() {
        snapshot = Snapshot(
            title = title,
            artist = artist,
            artwork = artwork,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            rate = rate,
            output = outputDeviceName(),
        )
        platformSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .apply { artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        publishState()
        publishNotification()
    }

    private fun publishState() {
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO
        platformSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    state,
                    positionMs,
                    if (state == PlaybackState.STATE_PLAYING) rate.toFloat().coerceAtLeast(0.1f) else 0f,
                )
                .build(),
        )
    }

    private fun publishNotification() {
        val manager = activity.getSystemService(NotificationManager::class.java)
        if (state == PlaybackState.STATE_NONE) {
            manager.cancel(BrowserMediaService.NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            manager.notify(
                BrowserMediaService.NOTIFICATION_ID,
                buildNotification(activity, platformSession?.sessionToken),
            )
        }
    }

    private fun tabForMedia(session: GeckoSession): Long? = activity.tabIdForSession(session)

    private fun outputDeviceName(): String = outputDevice
        ?.takeIf { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?.productName
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: ""

    data class Snapshot(
        val title: String = "",
        val artist: String = "",
        val artwork: Bitmap? = null,
        val state: Int = PlaybackState.STATE_NONE,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val rate: Double = 0.0,
        val output: String = "",
    )

    companion object {
        private const val ARTWORK_SIZE = 512

        @Volatile
        private var snapshot = Snapshot()

        @Volatile
        private var sessionToken: MediaSession.Token? = null

        @Volatile
        private var active: BrowserMediaController? = null

        /** The session token, so the foreground service posts the same notification. */
        fun mediaToken(): MediaSession.Token? = sessionToken

        internal fun setToken(token: MediaSession.Token?) {
            sessionToken = token
        }

        fun attach(controller: BrowserMediaController) {
            active = controller
        }

        /** Play/pause/stop from the media notification. */
        internal fun dispatch(action: String?) {
            val media = active?.geckoSession ?: return
            when (action) {
                ACTION_PLAY -> media.play()
                ACTION_PAUSE -> media.pause()
                ACTION_STOP -> media.stop()
            }
        }

        /**
         * The single media notification, shared by the foreground service and the
         * controller so only one entry ever appears in the shade.
         */
        fun buildNotification(context: Context, token: MediaSession.Token?): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.media_notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            val openIntent = PendingIntent.getActivity(
                context,
                9,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val playing = snapshot.state == PlaybackState.STATE_PLAYING
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(snapshot.title.ifBlank { context.getString(R.string.app_name) })
                .setContentText(
                    listOf(snapshot.artist, snapshot.output).filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { context.getString(R.string.media_playing) },
                )
                .setContentIntent(openIntent)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(Notification.MediaStyle().setMediaSession(token))
            snapshot.artwork?.let { builder.setLargeIcon(it) }
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        context,
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    ),
                    context.getString(if (playing) R.string.pause else R.string.play),
                    actionIntent(context, if (playing) ACTION_PAUSE else ACTION_PLAY),
                ).build(),
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stop),
                    context.getString(R.string.stop_media),
                    actionIntent(context, ACTION_STOP),
                ).build(),
            )
            return builder.build()
        }

        private fun actionIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, MediaActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        const val ACTION_PLAY = "com.palash.librewolfandroid.MEDIA_PLAY"
        const val ACTION_PAUSE = "com.palash.librewolfandroid.MEDIA_PAUSE"
        const val ACTION_STOP = "com.palash.librewolfandroid.MEDIA_STOP"
        private const val CHANNEL_ID = "media_playback"
    }
}

/** Handles the play/pause/stop buttons on the media notification. */
class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        BrowserMediaController.dispatch(intent?.action)
    }
}
