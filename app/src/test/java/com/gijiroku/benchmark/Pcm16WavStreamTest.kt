package com.gijiroku.benchmark

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16WavStreamTest {

    @Test
    fun writesValidMonoPcm16WavWithoutAFile() {
        val samples = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val output = ByteArrayOutputStream()

        Pcm16WavStream.write(output, samples, sampleRate = 16_000, bufferBytes = 4)

        val bytes = output.toByteArray()
        assertEquals(Pcm16WavStream.HEADER_BYTES + samples.size * 2, bytes.size)
        val loaded = WavLoader.load(ByteArrayInputStream(bytes))
        assertEquals(16_000, loaded.sampleRate)
        assertArrayEquals(samples, loaded.samples, 1f / Short.MAX_VALUE)
    }

    @Test
    fun emptyAudioStillProducesValidWavHeader() {
        val output = ByteArrayOutputStream()
        Pcm16WavStream.write(output, FloatArray(0), sampleRate = 16_000)

        val loaded = WavLoader.load(ByteArrayInputStream(output.toByteArray()))
        assertEquals(0, loaded.samples.size)
        assertEquals(0.0, loaded.durationSec, 0.0)
    }
}
