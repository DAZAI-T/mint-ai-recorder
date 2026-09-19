package com.gijiroku.benchmark

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Serializes in-memory mono float PCM as WAV directly to a destination stream. */
internal object Pcm16WavStream {
    const val HEADER_BYTES = 44
    private const val PCM16_BYTES = 2

    fun write(
        output: OutputStream,
        samples: FloatArray,
        sampleRate: Int,
        bufferBytes: Int = 64 * 1024
    ) {
        require(sampleRate > 0) { AppLanguage.text("不正なサンプルレートです", "Invalid sample rate") }
        require(bufferBytes >= PCM16_BYTES) { AppLanguage.text("送信バッファが小さすぎます", "Upload buffer is too small") }
        val dataBytes = samples.size.toLong() * PCM16_BYTES
        require(dataBytes <= Int.MAX_VALUE - HEADER_BYTES) { AppLanguage.text("音声が長すぎてWAV送信できません", "Audio is too long for WAV upload") }
        writeHeader(output, dataBytes, sampleRate)
        val buffer = ByteArray(bufferBytes - (bufferBytes % PCM16_BYTES))
        try {
            var sampleIndex = 0
            while (sampleIndex < samples.size) {
                var byteIndex = 0
                while (sampleIndex < samples.size && byteIndex < buffer.size) {
                    val sample = samples[sampleIndex++]
                    val pcm = when {
                        sample <= -1f -> Short.MIN_VALUE.toInt()
                        sample >= 1f -> Short.MAX_VALUE.toInt()
                        else -> (sample * Short.MAX_VALUE).roundToInt()
                    }
                    buffer[byteIndex++] = (pcm and 0xff).toByte()
                    buffer[byteIndex++] = ((pcm ushr 8) and 0xff).toByte()
                }
                output.write(buffer, 0, byteIndex)
            }
        } finally {
            buffer.fill(0)
        }
    }

    fun writeHeader(
        output: OutputStream,
        dataBytes: Long,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        require(sampleRate > 0 && channels > 0 && bitsPerSample == 16) { AppLanguage.text("不正なWAV形式です", "Invalid WAV format") }
        require(dataBytes >= 0L && dataBytes <= UINT32_MAX - 36L) { AppLanguage.text("音声が長すぎてWAV保存できません", "Audio is too long to save as WAV") }
        require(dataBytes % (channels * PCM16_BYTES) == 0L) { AppLanguage.text("PCMデータがサンプル境界に揃っていません", "PCM data is not aligned to sample boundaries") }
        val blockAlign = channels * (bitsPerSample / 8)
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36L + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
        try {
            output.write(header)
        } finally {
            header.fill(0)
        }
    }

    private const val UINT32_MAX = 0xffff_ffffL
}
