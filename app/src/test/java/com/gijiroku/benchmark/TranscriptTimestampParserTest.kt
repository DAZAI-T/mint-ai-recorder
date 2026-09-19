package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTimestampParserTest {
    @Test
    fun parsesMinuteAndHourRangesInDisplayOrder() {
        val text = "[00:05 - 00:12] 話者A: はじめます\n[01:02:03–01:02:09] 話者B: 続けます"

        val parsed = TranscriptTimestampParser.parse(text)

        assertEquals(listOf("00:05", "01:02:03"), parsed.map(TranscriptTimestamp::label))
        assertEquals(TimeRange(5_000, 12_000), parsed[0].range)
        assertEquals(TimeRange(3_723_000, 3_729_000), parsed[1].range)
        assertEquals(text.indexOf("00:05"), parsed[0].textStart)
    }

    @Test
    fun ignoresInvalidAndBackwardRanges() {
        val parsed = TranscriptTimestampParser.parse(
            "[00:61 - 01:10] invalid\n[02:00 - 01:59] backward\n本文だけ"
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun findsNearestTranscriptPositionWhenEvidenceTimeIsNotExact() {
        val text = "[00:05 - 00:12] 話者A: 開始\n[00:28 - 00:35] 話者B: 決定"

        val closest = TranscriptTimestampParser.closestTo(text, 30_400)

        assertEquals("00:28", closest?.label)
        assertEquals(text.indexOf("00:28"), closest?.textStart)
    }

    @Test
    fun acceptsAndNormalizesCloudMillisecondRanges() {
        val text = "[1130-2550] SPEAKER_A: an iPod\n[321529-326709] SPEAKER_A: a phone"

        val parsed = TranscriptTimestampParser.parse(text)

        assertEquals(listOf("00:01", "05:21"), parsed.map(TranscriptTimestamp::label))
        assertEquals(TimeRange(1_130, 2_550), parsed[0].range)
        assertEquals(TimeRange(321_529, 326_709), parsed[1].range)
        assertEquals(
            "[00:01 - 00:02] SPEAKER_A: an iPod\n[05:21 - 05:26] SPEAKER_A: a phone",
            TranscriptTimestampParser.normalizeMillisecondRanges(text)
        )
    }

    @Test
    fun convertsPointMillisecondRangesFromLongMeetings() {
        val text = "[791009-791009] SPEAKER_UNKNOWN: It's not too shabby."

        val parsed = TranscriptTimestampParser.parse(text)

        assertEquals("13:11", parsed.single().label)
        assertEquals(TimeRange(791_009, 792_009), parsed.single().range)
        assertEquals(
            "[13:11 - 13:12] SPEAKER_UNKNOWN: It's not too shabby.",
            TranscriptTimestampParser.normalizeMillisecondRanges(text)
        )
    }

    @Test
    fun restoresModelWrittenTimestampsFromTrustedTranscriptRanges() {
        val text = "[00:31 - 05:26] SPEAKER_A: widescreen\n[791009-791009] SPEAKER_B: call"

        val restored = TranscriptTimestampParser.restoreTrustedRanges(
            text,
            listOf(TimeRange(31_839, 32_679), TimeRange(791_009, 794_549))
        )

        assertEquals(
            "[00:31 - 00:32] SPEAKER_A: widescreen\n[13:11 - 13:14] SPEAKER_B: call",
            restored
        )
    }

    @Test
    fun sameSecondClockRangeRemainsPlayableAndIsNormalized() {
        val text = "[08:06 - 08:06] SPEAKER_UNKNOWN: Bye-bye."

        val parsed = TranscriptTimestampParser.parse(text)

        assertEquals(TimeRange(486_000, 487_000), parsed.single().range)
        assertEquals(
            "[08:06 - 08:07] SPEAKER_UNKNOWN: Bye-bye.",
            TranscriptTimestampParser.normalizeMillisecondRanges(text)
        )
    }

    @Test
    fun subSecondTrustedRangeDisplaysDifferentEndSecond() {
        assertEquals(
            "[08:06 - 08:07]",
            TranscriptTimestampParser.formatRange(486_089, 486_709)
        )
    }
}
