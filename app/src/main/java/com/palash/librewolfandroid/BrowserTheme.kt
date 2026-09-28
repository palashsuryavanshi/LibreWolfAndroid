package com.palash.librewolfandroid

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object BrowserTheme {
    /** 0 = follow system, 1 = light, 2 = dark. */
    fun mode(context: Context): Int = PrivacyStore(context).themeMode

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(context)) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }
}
