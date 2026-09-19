package com.gijiroku.benchmark

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Orchestrates one (model x audio x threads) benchmark: loads the model once, then runs
 * whisper_full [repeats] times, logging each run to CSV. Running the same clip repeatedly
 * (rather than once) is what makes thermal/battery drift measurable — see plan doc section 7.
 */
class BenchmarkRunner(private val context: Context) {

    private val csv = CsvExporter(context)

    fun run(
        modelFile: File,
        wav: WavAudio,
        audioFileLabel: String,
        nThreads: Int,
        repeats: Int,
        onLog: (String) -> Unit
    ) {
        val metrics = MetricsCollector(context)
        val batteryBefore = metrics.batteryPercent()

        onLog(AppLanguage.text("モデル読み込み中: ${modelFile.name} (${modelFile.length() / (1024 * 1024)}MB)", "Loading model: ${modelFile.name} (${modelFile.length() / (1024 * 1024)}MB)"))
        WhisperBridge().use { whisper ->
            whisper.load(modelFile.absolutePath)
            onLog(AppLanguage.text("読み込み完了。${repeats}回実行します（スレッド数=$nThreads）", "Loaded. Running ${repeats} times (threads=$nThreads)"))

            for (i in 1..repeats) {
                metrics.start()
                val startNs = System.nanoTime()
                val text = whisper.transcribe(wav.samples, nThreads)
                val wallMs = (System.nanoTime() - startNs) / 1_000_000
                val inferenceMs = whisper.lastInferenceMs().takeIf { it > 0 } ?: wallMs
                val result = metrics.stop()

                val rtf = inferenceMs.toDouble() / (wav.durationSec * 1000.0)

                csv.append(
                    BenchmarkRow(
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                        androidVersion = Build.VERSION.RELEASE ?: "?",
                        modelFile = modelFile.name,
                        modelSizeMb = modelFile.length() / (1024 * 1024),
                        audioFile = audioFileLabel,
                        audioDurationSec = wav.durationSec,
                        nThreads = nThreads,
                        runIndex = i,
                        inferenceMs = inferenceMs,
                        rtf = rtf,
                        peakMemoryMb = result.peakMemoryMb,
                        maxThermalStatus = result.maxThermalStatusName,
                        batteryBeforePct = batteryBefore,
                        batteryAfterPct = metrics.batteryPercent()
                    )
                )

                onLog(
                    "run $i/$repeats: inference=${inferenceMs}ms rtf=%.3f peakMem=${result.peakMemoryMb}MB thermal=${result.maxThermalStatusName}"
                        .format(rtf)
                )
                onLog(AppLanguage.text("  transcript先頭80字: ${text.take(80).replace("\n", " ")}", "  First 80 transcript characters: ${text.take(80).replace("\n", " ")}"))
            }
        }

        val batteryAfter = metrics.batteryPercent()
        onLog(AppLanguage.text("完了。バッテリー: ${batteryBefore}% → ${batteryAfter}%", "Complete. Battery: ${batteryBefore}% → ${batteryAfter}%"))
        onLog(AppLanguage.text("結果CSV: ${csv.file.absolutePath}", "Results CSV: ${csv.file.absolutePath}"))
    }
}
