package com.palash.librewolfandroid

import android.app.Application
import android.os.StrictMode

class LibreWolfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug builds only, and only as a detector: any disk read or write on
        // the main thread is logged with the offending stack. This is how the
        // claim "history persistence no longer runs on the UI thread" was
        // checked rather than asserted -- with the detector live and a positive
        // control proving it fires, a clean log over real navigation means the
        // main thread genuinely did none. StrictMode is never enabled in a
        // release build: it only logs, but it is not something to ship.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
        BrowserTheme.apply(this)
        DownloadEngine.init(this)
        DownloadService.ensureChannel(this)
    }
}
