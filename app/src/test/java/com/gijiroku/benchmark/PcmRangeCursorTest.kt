package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRangeCursorTest {
    @Test
    fun selectsOnlyRequestedTimeRangeAcrossChunks() {
        val cursor = PcmRangeCursor.forTimeRange(
            startMs = 1_000,
            endMs = 2_000,
            sampleRate = 1_000,
            channels = 1,
            bitsPerSample = 16
        )

        assertNull(cursor.take(1_500))
        assertEquals(PcmChunkSlice(500, 500), cursor.take(1_000))
        assertEquals(PcmChunkSlice(0, 1_500), cursor.take(2_000))
        assertTrue(cursor.reachedEnd)
    }

    @Test
    fun stopsAtRequestedEndInsideChunk() {
        val cursor = PcmRangeCursor(2, 6)

        assertEquals(PcmChunkSlice(2, 4), cursor.take(10))
        assertTrue(cursor.reachedEnd)
        assertNull(cursor.take(4))
    }
}
