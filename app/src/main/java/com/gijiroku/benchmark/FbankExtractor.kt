package com.gijiroku.benchmark

/**
 * Thin JNI wrapper around kaldi-native-fbank (see app/src/main/cpp/fbank_jni.cpp), configured
 * to match sherpa-onnx's feature extraction for its 3D-Speaker/WeSpeaker/CAM++ speaker-embedding
 * models: 16kHz, 80-dim log-mel filterbank, 25ms/10ms frame length/shift.
 */
object FbankExtractor {

    const val FEATURE_DIM = 80

    /** Returns [numFrames][FEATURE_DIM] log-mel filterbank frames for [samples] (mono, -1..1, 16kHz). */
    fun compute(samples: FloatArray, sampleRate: Int = 16000): Array<FloatArray> {
        val flat = nativeComputeFbank(samples, sampleRate)
        val numFrames = flat.size / FEATURE_DIM
        return Array(numFrames) { i ->
            flat.copyOfRange(i * FEATURE_DIM, (i + 1) * FEATURE_DIM)
        }
    }

    private external fun nativeComputeFbank(samples: FloatArray, sampleRate: Int): FloatArray

    init {
        System.loadLibrary("whisper_jni")
    }
}
