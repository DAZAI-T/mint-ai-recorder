package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSegmentSanitizerTest {
    @Test
    fun givesPointTimestampsAUsableAudioRange() {
        val repaired = TranscriptSegmentSanitizer.sanitize(
            listOf(
                TranscriptSegment(791_009, 791_009, "first"),
                TranscriptSegment(794_549, 794_549, "second"),
                TranscriptSegment(797_209, 797_209, "third")
            ),
            audioDurationMs = 800_000
        )

        assertEquals(
            listOf(
                TranscriptSegment(791_009, 792_509, "first"),
                TranscriptSegment(794_549, 796_049, "second"),
                TranscriptSegment(797_209, 798_709, "third")
            ),
            repaired
        )
    }

    @Test
    fun stopsAnInferredRangeAtTheNextTurn() {
        val repaired = TranscriptSegmentSanitizer.sanitize(
            listOf(
                TranscriptSegment(10_000, 10_000, "first"),
                TranscriptSegment(10_600, 11_000, "second")
            ),
            audioDurationMs = 20_000
        )

        assertEquals(TranscriptSegment(10_000, 10_600, "first"), repaired.first())
    }

    @Test
    fun repairsAPointTimestampAtTheEndOfTheRecording() {
        val repaired = TranscriptSegmentSanitizer.sanitize(
            listOf(TranscriptSegment(20_000, 20_000, "last")),
            audioDurationMs = 20_000
        )

        assertEquals(listOf(TranscriptSegment(18_500, 20_000, "last")), repaired)
    }
}
