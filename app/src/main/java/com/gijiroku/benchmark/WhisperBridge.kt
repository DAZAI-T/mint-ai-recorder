package com.gijiroku.benchmark

/**
 * Thin JNI wrapper around whisper.cpp's C API (see app/src/main/cpp/jni_bridge.cpp).
 * One instance wraps one loaded model (whisper_context).
 */
class WhisperBridge : AutoCloseable {

    private var ctxPtr: Long = 0L

    fun load(modelPath: String) {
        check(ctxPtr == 0L) { "already loaded" }
        ctxPtr = nativeInit(modelPath)
        check(ctxPtr != 0L) { "whisper_init_from_file_with_params failed for $modelPath" }
    }

    /**
     * Runs whisper_full on [samples] (mono float32, -1..1, 16kHz).
     * Returns the concatenated transcript. Call [lastInferenceMs] afterward for the
     * native-side wall time of the whisper_full call alone (excludes JNI array marshalling).
     */
    fun transcribe(samples: FloatArray, nThreads: Int, language: String = "ja"): String {
        check(ctxPtr != 0L) { "model not loaded" }
        return nativeTranscribe(ctxPtr, samples, nThreads, language)
    }

    fun lastInferenceMs(): Long {
        check(ctxPtr != 0L) { "model not loaded" }
        return nativeLastInferenceMs(ctxPtr)
    }

    /**
     * Same decoding pass as [transcribe], but returns per-segment timestamps so the result can
     * be merged with speaker-diarization segments by time (design.md 3章「文章と話者を時刻で結合」).
     */
    fun transcribeSegments(samples: FloatArray, nThreads: Int, language: String = "ja"): List<TranscriptSegment> {
        check(ctxPtr != 0L) { "model not loaded" }
        val raw = nativeTranscribeSegments(ctxPtr, samples, nThreads, language)
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("\t", limit = 3)
                TranscriptSegment(
                    startMs = parts[0].toLong(),
                    endMs = parts[1].toLong(),
                    text = parts.getOrElse(2) { "" }
                )
            }
            .toList()
    }

    override fun close() {
        if (ctxPtr != 0L) {
            nativeFree(ctxPtr)
            ctxPtr = 0L
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(ctxPtr: Long)
    private external fun nativeTranscribe(ctxPtr: Long, samples: FloatArray, nThreads: Int, language: String): String
    private external fun nativeTranscribeSegments(ctxPtr: Long, samples: FloatArray, nThreads: Int, language: String): String
    private external fun nativeLastInferenceMs(ctxPtr: Long): Long

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
