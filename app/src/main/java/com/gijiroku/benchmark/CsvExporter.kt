package com.gijiroku.benchmark

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BenchmarkRow(
    val deviceModel: String,
    val androidVersion: String,
    val modelFile: String,
    val modelSizeMb: Long,
    val audioFile: String,
    val audioDurationSec: Double,
    val nThreads: Int,
    val runIndex: Int,
    val inferenceMs: Long,
    val rtf: Double,
    val peakMemoryMb: Long,
    val maxThermalStatus: String,
    val batteryBeforePct: Int,
    val batteryAfterPct: Int,
    val notes: String = ""
)

/**
 * Appends benchmark results to a CSV under the external files directory. Creating the
 * header lazily keeps repeated runs across app restarts in one file per device.
 */
class CsvExporter(context: Context) {

    val file: File = File(context.getExternalFilesDir(null), "benchmark_results.csv")

    private val header = listOf(
        "timestamp", "device_model", "android_version", "model_file", "model_size_mb",
        "audio_file", "audio_duration_sec", "n_threads", "run_index",
        "inference_ms", "rtf", "peak_memory_mb", "max_thermal_status",
        "battery_before_pct", "battery_after_pct", "notes"
    ).joinToString(",")

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    fun append(row: BenchmarkRow) {
        if (!file.exists()) {
            file.writeText(header + "\n")
        }
        val fields = listOf(
            timestampFormat.format(Date()),
            row.deviceModel,
            row.androidVersion,
            row.modelFile,
            row.modelSizeMb,
            row.audioFile,
            "%.2f".format(row.audioDurationSec),
            row.nThreads,
            row.runIndex,
            row.inferenceMs,
            "%.4f".format(row.rtf),
            row.peakMemoryMb,
            row.maxThermalStatus,
            row.batteryBeforePct,
            row.batteryAfterPct,
            row.notes
        ).joinToString(",") { csvEscape(it.toString()) }
        file.appendText(fields + "\n")
    }

    private fun csvEscape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
