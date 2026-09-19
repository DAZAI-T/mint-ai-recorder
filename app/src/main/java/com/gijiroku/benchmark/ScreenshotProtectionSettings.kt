package com.gijiroku.benchmark

import android.content.Context

/** Controls Android FLAG_SECURE independently from the application lock setting. */
class ScreenshotProtectionSettings(context: Context) {
    private val preferences = (context.applicationContext ?: context).getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var screenshotsAllowed: Boolean
        get() = preferences.getBoolean(KEY_SCREENSHOTS_ALLOWED, false)
        set(value) = preferences.edit().putBoolean(KEY_SCREENSHOTS_ALLOWED, value).apply()

    companion object {
        private const val PREFERENCES_NAME = "gijiroku_screenshot_protection"
        private const val KEY_SCREENSHOTS_ALLOWED = "screenshots_allowed"
    }
}
