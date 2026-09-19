package com.gijiroku.benchmark

import java.io.InputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavAudio(val samples: FloatArray, val sampleRate: Int, val durationSec: Double)

/**
 * Minimal RIFF/WAVE reader. Validates the app's own recording format
 * (16kHz / 16-bit PCM / mono, per the design doc section 4) rather than
 * attempting general-purpose resampling/downmixing.
 */
object WavLoader {

    fun load(input: InputStream): WavAudio {
        val stream = input.buffered()
        val riff = readFully(stream, 12)
        require(riff.decodeAscii(0, 4) == "RIFF" && riff.decodeAscii(8, 4) == "WAVE") {
            "not a RIFF/WAVE file"
        }

        var sampleRate = 0
        var bitsPerSample = 0
        var channels = 0
        var pcmData: ByteArray? = null

        while (true) {
            val header = try {
                readFully(stream, 8)
            } catch (e: EOFException) {
                break
            }
            val chunkId = header.decodeAscii(0, 4)
            val chunkSize = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = readFully(stream, chunkSize)
            // chunks are word-aligned; skip the pad byte for odd-sized chunks
            if (chunkSize % 2 == 1) readFully(stream, 1)

            when (chunkId) {
                "fmt " -> {
                    val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = bb.short.toInt()
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int // byte rate
                    bb.short // block align
                    bitsPerSample = bb.short.toInt()
                    require(audioFormat == 1) { "only PCM WAV is supported, got format=$audioFormat" }
                }
                "data" -> pcmData = body
                else -> { /* ignore unknown chunks (LIST, fact, etc.) */ }
            }
        }

        requireNotNull(pcmData) { "no data chunk found" }
        require(sampleRate == 16000) { "expected 16kHz, got ${sampleRate}Hz — resample with ffmpeg first" }
        require(bitsPerSample == 16) { "expected 16-bit PCM, got ${bitsPerSample}bit" }
        require(channels == 1) { "expected mono, got $channels channels" }

        val bb = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val nSamples = pcmData.size / 2
        val samples = FloatArray(nSamples)
        for (i in 0 until nSamples) {
            samples[i] = bb.short / 32768f
        }
        return WavAudio(samples, sampleRate, nSamples.toDouble() / sampleRate)
    }

    private fun readFully(stream: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = stream.read(buf, off, n - off)
            if (read < 0) throw EOFException()
            off += read
        }
        return buf
    }

    private fun ByteArray.decodeAscii(offset: Int, len: Int): String =
        String(this, offset, len, Charsets.US_ASCII)
}
