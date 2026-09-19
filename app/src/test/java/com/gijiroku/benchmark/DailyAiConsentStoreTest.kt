package com.gijiroku.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyAiConsentStoreTest {
    @Test
    fun grantIsValidForLessThanTwentyFourHours() {
        val grantedAt = 1_000_000L
        assertTrue(DailyAiConsentStore.isGrantValid(grantedAt, grantedAt))
        assertTrue(
            DailyAiConsentStore.isGrantValid(
                grantedAt,
                grantedAt + DailyAiConsentStore.VALIDITY_MS - 1
            )
        )
    }

    @Test
    fun expiryAndClockRollbackRequireConfirmationAgain() {
        val grantedAt = 1_000_000L
        assertFalse(
            DailyAiConsentStore.isGrantValid(
                grantedAt,
                grantedAt + DailyAiConsentStore.VALIDITY_MS
            )
        )
        assertFalse(DailyAiConsentStore.isGrantValid(grantedAt, grantedAt - 1))
    }
}
