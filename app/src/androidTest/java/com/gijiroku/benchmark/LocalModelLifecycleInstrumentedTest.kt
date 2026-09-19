package com.gijiroku.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelLifecycleInstrumentedTest {
    @Test
    fun installedModelsLoadSequentiallyAndReleaseTheirRuntime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ModelStore(context)
        val installed = listOf(
            store.hasFinalModel(),
            store.hasSpeakerModel(),
            store.hasQwenSummaryModel()
        )
        assumeTrue("No official local model is installed", installed.any { it })
        val totalMemory = LocalModelDeviceCapabilityEvaluator.evaluate(context).reportedRamBytes
        var peakPssBytes = processPssBytes(context)

        if (installed[0]) {
            LocalModelRuntimeCoordinator.withExclusiveModel(context, LocalModelKind.FINAL_WHISPER) {
                assertEquals(LocalModelKind.FINAL_WHISPER, LocalModelRuntimeCoordinator.activeModelForTest())
                WhisperBridge().use { it.load(store.finalModelPath()) }
                peakPssBytes = maxOf(peakPssBytes, processPssBytes(context))
                Log.i(LOG_TAG, "final_whisper_loaded_pss_bytes=$peakPssBytes")
            }
            assertNull(LocalModelRuntimeCoordinator.activeModelForTest())
        }
        if (installed[1]) {
            LocalModelRuntimeCoordinator.withExclusiveModel(context, LocalModelKind.SPEAKER_EMBEDDING) {
                assertEquals(LocalModelKind.SPEAKER_EMBEDDING, LocalModelRuntimeCoordinator.activeModelForTest())
                SpeakerEmbeddingExtractor(store.speakerModelPath()).use { }
                peakPssBytes = maxOf(peakPssBytes, processPssBytes(context))
                Log.i(LOG_TAG, "speaker_embedding_loaded_pss_bytes=$peakPssBytes")
            }
            assertNull(LocalModelRuntimeCoordinator.activeModelForTest())
        }
        if (installed[2]) {
            LocalModelRuntimeCoordinator.withExclusiveModel(context, LocalModelKind.QWEN_SUMMARY) {
                assertEquals(LocalModelKind.QWEN_SUMMARY, LocalModelRuntimeCoordinator.activeModelForTest())
                LlamaNativeBridge.openSession(store.qwenSummaryModelPath(), 8_192, 6).use { session ->
                    assertTrue(session.countTokens("system", "user") > 0)
                    val output = session.generate(
                        systemPrompt = "You are a runtime verification assistant. /no_think",
                        userPrompt = "Reply with OK only.",
                        maxOutputTokens = 8
                    )
                    assertTrue("Qwen must complete a short inference", output.isNotBlank())
                }
                peakPssBytes = maxOf(peakPssBytes, processPssBytes(context))
                Log.i(LOG_TAG, "qwen_generated_pss_bytes=$peakPssBytes")
            }
            assertNull(LocalModelRuntimeCoordinator.activeModelForTest())
        }

        assertTrue("Process PSS must stay below physical RAM", peakPssBytes in 1 until totalMemory)
        Log.i(LOG_TAG, "peak_pss_bytes=$peakPssBytes total_ram_bytes=$totalMemory")
    }

    private fun processPssBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            .firstOrNull()?.totalPss?.toLong()?.times(1024) ?: 0L
    }

    private companion object {
        const val LOG_TAG = "LocalModelLifecycle"
    }
}
