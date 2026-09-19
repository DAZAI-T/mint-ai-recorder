package com.gijiroku.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSessionTest {

    @Test
    fun startsLockedAndRelocksAfterBackgroundTimeout() {
        var now = 1_000L
        val session = AppLockSession(clock = { now }, timeoutMs = 300_000)

        assertTrue(session.requiresAuthentication(enabled = true))
        session.markAuthenticated()
        assertFalse(session.requiresAuthentication(enabled = true))

        session.markBackgrounded()
        now += 299_999
        assertFalse(session.requiresAuthentication(enabled = true))

        session.markBackgrounded()
        now += 300_000
        assertTrue(session.requiresAuthentication(enabled = true))
    }

    @Test
    fun disabledSettingNeverRequiresAuthentication() {
        val session = AppLockSession(clock = { 0 })
        assertFalse(session.requiresAuthentication(enabled = false))
        session.lockNow()
        assertFalse(session.requiresAuthentication(enabled = false))
    }
}
