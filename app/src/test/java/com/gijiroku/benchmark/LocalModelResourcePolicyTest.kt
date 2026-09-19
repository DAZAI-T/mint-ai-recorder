package com.gijiroku.benchmark

import android.os.PowerManager
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelResourcePolicyTest {
    private val ready = LocalModelResourceState(
        availableMemoryBytes = 8L * 1024 * 1024 * 1024,
        lowMemory = false,
        usableStorageBytes = 10L * 1024 * 1024 * 1024,
        batteryPercent = 80,
        charging = false,
        thermalStatus = PowerManager.THERMAL_STATUS_NONE
    )

    @Test
    fun acceptsHealthyDeviceState() {
        assertNull(LocalModelResourcePolicy.blockingReason(LocalModelKind.QWEN_SUMMARY, ready))
    }

    @Test
    fun rejectsInsufficientAvailableMemory() {
        val reason = LocalModelResourcePolicy.blockingReason(
            LocalModelKind.QWEN_SUMMARY,
            ready.copy(availableMemoryBytes = 2L * 1024 * 1024 * 1024)
        )
        assertTrue(reason.orEmpty().contains("空きメモリ"))
    }

    @Test
    fun rejectsSevereThermalState() {
        val reason = LocalModelResourcePolicy.blockingReason(
            LocalModelKind.FINAL_WHISPER,
            ready.copy(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE)
        )
        assertTrue(reason.orEmpty().contains("高温"))
    }

    @Test
    fun lowBatteryIsAllowedOnlyWhileCharging() {
        val blocked = LocalModelResourcePolicy.blockingReason(
            LocalModelKind.SPEAKER_EMBEDDING,
            ready.copy(batteryPercent = 5, charging = false)
        )
        val allowed = LocalModelResourcePolicy.blockingReason(
            LocalModelKind.SPEAKER_EMBEDDING,
            ready.copy(batteryPercent = 5, charging = true)
        )
        assertTrue(blocked.orEmpty().contains("電池残量"))
        assertNull(allowed)
    }

    @Test
    fun continuationIgnoresMemoryConsumedByTheActiveModelButStopsForHeat() {
        val lowAfterLoad = ready.copy(availableMemoryBytes = 128L * 1024 * 1024)
        assertNull(
            LocalModelResourcePolicy.continuationBlockingReason(LocalModelKind.QWEN_SUMMARY, lowAfterLoad)
        )
        val hot = LocalModelResourcePolicy.continuationBlockingReason(
            LocalModelKind.QWEN_SUMMARY,
            lowAfterLoad.copy(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE)
        )
        assertTrue(hot.orEmpty().contains("中断"))
    }
}
