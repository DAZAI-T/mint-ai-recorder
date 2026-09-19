package com.gijiroku.benchmark

import android.app.ActivityManager
import android.content.Context

data class LocalModelDeviceCapabilities(
    val reportedRamBytes: Long,
    val usableStorageBytes: Long
) {
    val hasRecommendedRam: Boolean
        get() = reportedRamBytes >= MIN_ANDROID_REPORTED_RAM_BYTES

    val hasRequiredStorage: Boolean
        get() = usableStorageBytes >= MIN_FREE_STORAGE_BYTES

    val isFullLocalRecommended: Boolean
        get() = hasRecommendedRam && hasRequiredStorage

    companion object {
        // Android excludes hardware-reserved memory from ActivityManager.totalMem. A marketed
        // 12 GB device normally reports roughly 11 GB or more to Android.
        const val MIN_ANDROID_REPORTED_RAM_BYTES = 11_000_000_000L
        const val MIN_FREE_STORAGE_BYTES = 8_000_000_000L
    }
}

object LocalModelDeviceCapabilityEvaluator {
    fun evaluate(context: Context): LocalModelDeviceCapabilities {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return LocalModelDeviceCapabilities(
            reportedRamBytes = memory.totalMem,
            usableStorageBytes = context.filesDir.usableSpace
        )
    }
}
