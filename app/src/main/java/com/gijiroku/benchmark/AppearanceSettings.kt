package com.gijiroku.benchmark

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class AppearanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var mode: Int
        get() = prefs.getInt("mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) { prefs.edit().putInt("mode", value).apply() }
    fun apply() { AppCompatDelegate.setDefaultNightMode(mode) }
}
