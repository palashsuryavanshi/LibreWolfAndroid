package com.palash.librewolfandroid

import android.app.Application

class LibreWolfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BrowserTheme.apply(this)
        DownloadEngine.init(this)
        DownloadService.ensureChannel(this)
    }
}
