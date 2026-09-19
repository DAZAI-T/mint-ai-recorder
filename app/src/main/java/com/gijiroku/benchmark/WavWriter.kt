package com.gijiroku.benchmark

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming 16kHz/16bit/mono PCM WAV writer: writes a placeholder header up front so audio
 * can be appended as it arrives from AudioRecord, then patches the header's size fields on
 * [finish] once the final byte count is known.
 */
class WavWriter(file: File, private val sampleRate: Int = 16000) {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytesWritten = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(HEADER_SIZE))
    }

    fun appendPcm16(samples: ShortArray, length: Int) {
        val buf = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) buf.putShort(samples[i])
        raf.write(buf.array())
        dataBytesWritten += length * 2L
    }

    fun finish() {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytesWritten).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((CHANNELS * (BITS_PER_SAMPLE / 8)).toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytesWritten.toInt())
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }

    companion object {
        private const val HEADER_SIZE = 44
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }
}
