package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiWhisperProviderTest {

    @Test
    fun verboseResponseUsesSegmentTimestamps() {
        val segments = OpenAiWhisperProvider.parseVerboseTranscript(
            """
                {
                  "text": "こんにちは。次の議題です。",
                  "segments": [
                    {"start": 0.25, "end": 1.75, "text": " こんにちは。 "},
                    {"start": 2.0, "end": 4.125, "text": "次の議題です。"}
                  ]
                }
            """.trimIndent(),
            fallbackDurationMs = 10_000
        )

        assertEquals(
            listOf(
                TranscriptSegment(250, 1_750, "こんにちは。"),
                TranscriptSegment(2_000, 4_125, "次の議題です。")
            ),
            segments
        )
    }

    @Test
    fun responseWithoutSegmentsFallsBackToWholeRecording() {
        val segments = OpenAiWhisperProvider.parseVerboseTranscript(
            """{"text":" テストです "}""",
            fallbackDurationMs = 42_000
        )

        assertEquals(listOf(TranscriptSegment(0, 42_000, "テストです")), segments)
    }

    @Test
    fun whisperCostIsCalculatedFromAudioDuration() {
        assertEquals(0.009, OpenAiWhisperProvider.estimateCostUsd(90_000), 0.000_000_1)
    }
}
