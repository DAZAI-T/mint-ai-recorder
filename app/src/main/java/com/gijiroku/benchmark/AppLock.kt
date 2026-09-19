package com.gijiroku.benchmark

import android.content.Context
import android.os.SystemClock

class AppLockSettings(context: Context) {
    private val preferences = (context.applicationContext ?: context).getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    companion object {
        const val BACKGROUND_TIMEOUT_MS = 5 * 60 * 1000L
        private const val PREFERENCES_NAME = "gijiroku_app_lock"
        private const val KEY_ENABLED = "enabled"
    }
}

internal class AppLockSession(
    private val clock: () -> Long,
    private val timeoutMs: Long = AppLockSettings.BACKGROUND_TIMEOUT_MS
) {
    private var authenticated = false
    private var backgroundAt: Long? = null

    @Synchronized
    fun requiresAuthentication(enabled: Boolean): Boolean {
        if (!enabled) return false
        if (!authenticated) return true
        val leftAt = backgroundAt ?: return false
        if (clock() - leftAt >= timeoutMs) {
            authenticated = false
            return true
        }
        backgroundAt = null
        return false
    }

    @Synchronized
    fun markAuthenticated() {
        authenticated = true
        backgroundAt = null
    }

    @Synchronized
    fun markBackgrounded() {
        if (authenticated && backgroundAt == null) backgroundAt = clock()
    }

    @Synchronized
    fun lockNow() {
        authenticated = false
        backgroundAt = null
    }
}

object AppLockController {
    private val session = AppLockSession(SystemClock::elapsedRealtime)

    fun requiresAuthentication(context: Context): Boolean =
        session.requiresAuthentication(AppLockSettings(context).enabled)

    fun markAuthenticated() = session.markAuthenticated()

    fun markBackgrounded() = session.markBackgrounded()

    fun lockNow() = session.lockNow()
}
