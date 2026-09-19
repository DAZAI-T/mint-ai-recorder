package com.gijiroku.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelDeviceCapabilitiesTest {
    @Test
    fun fullLocalRequiresBothRamAndStorage() {
        val enough = LocalModelDeviceCapabilities(
            reportedRamBytes = LocalModelDeviceCapabilities.MIN_ANDROID_REPORTED_RAM_BYTES,
            usableStorageBytes = LocalModelDeviceCapabilities.MIN_FREE_STORAGE_BYTES
        )
        assertTrue(enough.hasRecommendedRam)
        assertTrue(enough.hasRequiredStorage)
        assertTrue(enough.isFullLocalRecommended)

        assertFalse(enough.copy(reportedRamBytes = enough.reportedRamBytes - 1).isFullLocalRecommended)
        assertFalse(enough.copy(usableStorageBytes = enough.usableStorageBytes - 1).isFullLocalRecommended)
    }

    @Test
    fun lowCapabilityDoesNotEraseIndividualSignals() {
        val lowRam = LocalModelDeviceCapabilities(
            reportedRamBytes = 8_000_000_000L,
            usableStorageBytes = 20_000_000_000L
        )
        assertFalse(lowRam.hasRecommendedRam)
        assertTrue(lowRam.hasRequiredStorage)
        assertFalse(lowRam.isFullLocalRecommended)
    }
}
