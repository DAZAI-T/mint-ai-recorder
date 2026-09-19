package com.gijiroku.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class MetricsResult(
    val peakMemoryMb: Long,
    val maxThermalStatus: Int,
    val maxThermalStatusName: String
)

/** Thermal status ints per PowerManager (API 29+); named here so pre-29 devices still get a label. */
private val THERMAL_STATUS_NAMES = listOf(
    "NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"
)

/**
 * Polls process memory (PSS) and device thermal status on a background thread while a
 * benchmark run is in flight. Battery level is read once before and once after the run
 * (it doesn't move fast enough within a single run to be worth polling) — see [batteryPercent].
 */
class MetricsCollector(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var pollThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val peakMemoryKb = AtomicLong(0)
    private val maxThermal = AtomicInteger(0)

    fun start(pollIntervalMs: Long = 500) {
        peakMemoryKb.set(0)
        maxThermal.set(0)
        running.set(true)
        pollThread = Thread {
            val pid = Process.myPid()
            while (running.get()) {
                val memInfos = activityManager.getProcessMemoryInfo(intArrayOf(pid))
                val pss = memInfos.firstOrNull()?.totalPss?.toLong() ?: 0L
                if (pss > peakMemoryKb.get()) peakMemoryKb.set(pss)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val status = powerManager.currentThermalStatus
                    if (status > maxThermal.get()) maxThermal.set(status)
                }

                try {
                    Thread.sleep(pollIntervalMs)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop(): MetricsResult {
        running.set(false)
        pollThread?.interrupt()
        pollThread?.join(1000)
        pollThread = null
        val status = maxThermal.get()
        return MetricsResult(
            peakMemoryMb = peakMemoryKb.get() / 1024,
            maxThermalStatus = status,
            maxThermalStatusName = THERMAL_STATUS_NAMES.getOrElse(status) { "UNKNOWN($status)" }
        )
    }

    fun batteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
