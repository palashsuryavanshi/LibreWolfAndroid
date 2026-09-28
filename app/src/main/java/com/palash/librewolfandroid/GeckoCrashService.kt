package com.palash.librewolfandroid

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/** Receives GeckoView crash metadata without uploading or persisting page content. */
class GeckoCrashService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val minidump = intent?.getBooleanExtra(EXTRA_MINIDUMP, false) == true
        val crashTime = intent?.getLongExtra(EXTRA_CRASH_TIME, 0L) ?: 0L
        Log.w(TAG, "Gecko content crash received (minidump=$minidump, time=$crashTime)")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LibreWolfCrash"
        private const val EXTRA_MINIDUMP = "minidump"
        private const val EXTRA_CRASH_TIME = "crashTime"
    }
}
