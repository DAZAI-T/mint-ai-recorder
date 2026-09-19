package com.gijiroku.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

enum class LocalModelKind(
    private val names: Pair<String, String>,
    val minimumAvailableMemoryBytes: Long
) {
    LIVE_WHISPER("軽量Whisper" to "Lightweight Whisper", 768L * 1024 * 1024),
    FINAL_WHISPER("高精度Whisper" to "High-accuracy Whisper", 1_500L * 1024 * 1024),
    SPEAKER_EMBEDDING("CAM++話者分離" to "CAM++ speaker separation", 512L * 1024 * 1024),
    QWEN_SUMMARY("Qwen3-4B要約" to "Qwen3-4B summary", 4_000L * 1024 * 1024);

    val displayName: String get() = AppLanguage.text(names.first, names.second)
}

data class LocalModelResourceState(
    val availableMemoryBytes: Long,
    val lowMemory: Boolean,
    val usableStorageBytes: Long,
    val batteryPercent: Int?,
    val charging: Boolean,
    val thermalStatus: Int
)

object LocalModelResourcePolicy {
    const val MIN_RUNTIME_STORAGE_BYTES = 256L * 1024 * 1024
    const val MIN_BATTERY_PERCENT = 10

    fun blockingReason(kind: LocalModelKind, state: LocalModelResourceState): String? = when {
        state.lowMemory || state.availableMemoryBytes < kind.minimumAvailableMemoryBytes ->
            AppLanguage.text("${kind.displayName}を開始する空きメモリが不足しています。ほかのアプリを閉じてから再実行してください", "Not enough memory to start ${kind.displayName}. Close other apps and try again.")
        state.usableStorageBytes < MIN_RUNTIME_STORAGE_BYTES ->
            AppLanguage.text("端末の空き容量が少ないため${kind.displayName}を開始できません。空き容量を増やしてください", "Not enough storage to start ${kind.displayName}. Free up space and try again.")
        state.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
            AppLanguage.text("端末が高温のため${kind.displayName}を開始しません。冷ましてから同じ録音で再実行してください", "Device is too hot to start ${kind.displayName}. Let it cool down and retry this recording.")
        state.batteryPercent != null && state.batteryPercent < MIN_BATTERY_PERCENT && !state.charging ->
            AppLanguage.text("電池残量が10%未満のため${kind.displayName}を開始しません。充電してから再実行してください", "Battery is below 10%. Charge the device before starting ${kind.displayName}.")
        else -> null
    }

    fun continuationBlockingReason(kind: LocalModelKind, state: LocalModelResourceState): String? = when {
        state.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
            AppLanguage.text("端末が高温になったため${kind.displayName}を中断しました。冷ましてから続きから再実行してください", "${kind.displayName} stopped because the device is hot. Let it cool down and resume.")
        state.batteryPercent != null && state.batteryPercent < MIN_BATTERY_PERCENT && !state.charging ->
            AppLanguage.text("電池残量が10%未満になったため${kind.displayName}を中断しました。充電してから続きから再実行してください", "${kind.displayName} stopped because battery is below 10%. Charge and resume.")
        else -> null
    }
}

object LocalModelResourceReader {
    fun read(context: Context): LocalModelResourceState {
        val appContext = context.applicationContext
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (batteryLevel >= 0 && batteryScale > 0) batteryLevel * 100 / batteryScale else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return LocalModelResourceState(
            availableMemoryBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
            usableStorageBytes = appContext.filesDir.usableSpace,
            batteryPercent = percent,
            charging = charging,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager.currentThermalStatus
            } else {
                PowerManager.THERMAL_STATUS_NONE
            }
        )
    }
}

/** Process-wide gate: native model weights from different stages are never resident concurrently. */
object LocalModelRuntimeCoordinator {
    private val lock = ReentrantLock(true)
    @Volatile private var activeKind: LocalModelKind? = null

    fun <T> withExclusiveModel(context: Context, kind: LocalModelKind, block: () -> T): T {
        check(!lock.isHeldByCurrentThread) { AppLanguage.text("ローカルAIモデルを同じスレッドで重ねて実行できません", "Cannot nest local AI execution on the same thread") }
        val acquired = try {
            lock.tryLock(15, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        check(acquired) {
            AppLanguage.text("別のローカルAI処理が実行中です。完了してから${kind.displayName}を再実行してください", "Another local AI task is running. Wait before retrying ${kind.displayName}.")
        }
        try {
            val reason = LocalModelResourcePolicy.blockingReason(kind, LocalModelResourceReader.read(context))
            check(reason == null) { reason.orEmpty() }
            check(activeKind == null) { AppLanguage.text("ローカルAIモデルの実行状態が競合しました", "Conflicting local AI execution state") }
            activeKind = kind
            return block()
        } finally {
            activeKind = null
            lock.unlock()
        }
    }

    internal fun activeModelForTest(): LocalModelKind? = activeKind

    fun requireSafeToContinue(context: Context, kind: LocalModelKind) {
        val reason = LocalModelResourcePolicy.continuationBlockingReason(
            kind,
            LocalModelResourceReader.read(context)
        )
        check(reason == null) { reason.orEmpty() }
    }
}
