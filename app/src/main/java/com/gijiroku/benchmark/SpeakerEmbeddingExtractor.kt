package com.gijiroku.benchmark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Speaker embedding extractor for sherpa-onnx-compatible 3D-Speaker/WeSpeaker/CAM++ ONNX models
 * (e.g. 3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx). Feeds 80-dim log-mel fbank frames
 * (FbankExtractor) with per-utterance global-mean subtraction, replicating
 * SpeakerEmbeddingExtractorGeneralImpl::Compute in k2-fsa/sherpa-onnx so the features match
 * what these models were trained/exported against.
 */
class SpeakerEmbeddingExtractor(modelPath: String) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName = session.inputNames.iterator().next()

    /** [samples]: mono float32 (-1..1) at 16kHz. Returns null if too short to yield any fbank frame. */
    fun embed(samples: FloatArray): FloatArray? {
        val frames = FbankExtractor.compute(samples)
        if (frames.isEmpty()) return null
        subtractGlobalMean(frames)

        val dim = FbankExtractor.FEATURE_DIM
        val buffer = FloatBuffer.allocate(frames.size * dim)
        for (frame in frames) buffer.put(frame)
        buffer.rewind()

        OnnxTensor.createTensor(env, buffer, longArrayOf(1, frames.size.toLong(), dim.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                return flattenEmbedding(result[0].value)
            }
        }
    }

    /** Matches SubtractGlobalMean in sherpa-onnx: per-dimension mean across the whole utterance, subtracted per frame. */
    private fun subtractGlobalMean(frames: Array<FloatArray>) {
        val dim = frames[0].size
        val mean = FloatArray(dim)
        for (frame in frames) for (i in 0 until dim) mean[i] += frame[i]
        for (i in 0 until dim) mean[i] /= frames.size
        for (frame in frames) for (i in 0 until dim) frame[i] -= mean[i]
    }

    private fun flattenEmbedding(output: Any?): FloatArray {
        @Suppress("UNCHECKED_CAST")
        return when (output) {
            is Array<*> -> (output as Array<FloatArray>)[0] // shape [1, dim]
            is FloatArray -> output
            else -> throw IllegalStateException("unexpected embedding output type: ${output?.javaClass}")
        }
    }

    override fun close() {
        session.close()
    }
}
