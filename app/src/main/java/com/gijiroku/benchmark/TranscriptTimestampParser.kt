package com.gijiroku.benchmark

data class TranscriptTimestamp(
    val label: String,
    val textStart: Int,
    val textEndExclusive: Int,
    val range: TimeRange
)

/** Extracts the leading timestamp from transcript ranges such as [01:05 - 01:12]. */
object TranscriptTimestampParser {
    private val rangePattern = Regex(
        """\[(\d{1,3}:[0-5]\d(?::[0-5]\d)?)\s*[-–—]\s*(\d{1,3}:[0-5]\d(?::[0-5]\d)?)\]"""
    )
    private val millisecondRangePattern = Regex(
        """(?m)^\s*\[(\d{1,10})\s*[-–—]\s*(\d{1,10})\]"""
    )
    private val timestampPrefixPattern = Regex(
        """(?m)^(\s*)\[(?:\d{1,3}:[0-5]\d(?::[0-5]\d)?\s*[-–—]\s*\d{1,3}:[0-5]\d(?::[0-5]\d)?|\d{1,10}\s*[-–—]\s*\d{1,10})\]"""
    )

    fun parse(text: String): List<TranscriptTimestamp> {
        val clockRanges = rangePattern.findAll(text).mapNotNull { match ->
            val start = parseTime(match.groupValues[1]) ?: return@mapNotNull null
            val end = parseTime(match.groupValues[2]) ?: return@mapNotNull null
            val playableEnd = normalizeEnd(start, end) ?: return@mapNotNull null
            val labelRange = match.groups[1]?.range ?: return@mapNotNull null
            TranscriptTimestamp(
                label = match.groupValues[1],
                textStart = labelRange.first,
                textEndExclusive = labelRange.last + 1,
                range = TimeRange(start, playableEnd)
            )
        }
        val millisecondRanges = millisecondRangePattern.findAll(text).mapNotNull { match ->
            val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val end = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val playableEnd = normalizeEnd(start, end) ?: return@mapNotNull null
            val labelRange = match.groups[1]?.range ?: return@mapNotNull null
            TranscriptTimestamp(
                label = formatTime(start),
                textStart = labelRange.first,
                textEndExclusive = labelRange.last + 1,
                range = TimeRange(start, playableEnd)
            )
        }
        return (clockRanges + millisecondRanges).sortedBy(TranscriptTimestamp::textStart).toList()
    }

    /** Converts cloud-model output such as [1130-2550] into the app's display format. */
    fun normalizeMillisecondRanges(text: String): String {
        val normalizedMilliseconds = millisecondRangePattern.replace(text) { match ->
            val start = match.groupValues[1].toLongOrNull()
            val end = match.groupValues[2].toLongOrNull()
            val displayEnd = if (start == null || end == null) null else displayEnd(start, end)
            if (start == null || displayEnd == null) {
                match.value
            } else {
                val leadingWhitespace = match.value.takeWhile(Char::isWhitespace)
                "$leadingWhitespace${formatRange(start, displayEnd)}"
            }
        }
        return rangePattern.replace(normalizedMilliseconds) { match ->
            val start = parseTime(match.groupValues[1]) ?: return@replace match.value
            val end = parseTime(match.groupValues[2]) ?: return@replace match.value
            if (end != start) match.value else formatRange(start, start + POINT_RANGE_DURATION_MS)
        }
    }

    /**
     * Replaces model-written timestamps with the trusted ranges from the transcript artifact.
     * The replacement is only performed when there is exactly one timestamped output line per
     * source turn, so a missing or added model line cannot shift every following timestamp.
     */
    fun restoreTrustedRanges(text: String, ranges: List<TimeRange>): String {
        val matches = timestampPrefixPattern.findAll(text).toList()
        if (ranges.isEmpty() || matches.size != ranges.size) return normalizeMillisecondRanges(text)
        var index = 0
        return timestampPrefixPattern.replace(text) { match ->
            val range = ranges[index++]
            val start = range.startMs.coerceIn(0, MAX_MILLISECONDS)
            val end = displayEnd(start, range.endMs) ?: start
            "${match.groupValues[1]}${formatRange(start, end)}"
        }
    }

    /** Formats a range so sub-second speech never appears as the same start and end second. */
    fun formatRange(startMs: Long, endMs: Long): String {
        val start = startMs.coerceIn(0, MAX_MILLISECONDS)
        val end = displayEnd(start, endMs) ?: start
        return "[${formatTime(start)} - ${formatTime(end)}]"
    }

    /** Finds the transcript range nearest to an LLM evidence timestamp. */
    fun closestTo(text: String, targetMs: Long): TranscriptTimestamp? =
        parse(text).minByOrNull { timestamp ->
            kotlin.math.abs(timestamp.range.startMs - targetMs.coerceAtLeast(0))
        }

    private fun parseTime(value: String): Long? {
        val parts = value.split(':').map { it.toLongOrNull() ?: return null }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3_600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return seconds.takeIf { it <= MAX_SECONDS }?.times(1_000)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun normalizeEnd(start: Long, end: Long): Long? {
        if (start !in 0..MAX_MILLISECONDS || end !in start..MAX_MILLISECONDS) return null
        if (end > start) return end
        return (start + POINT_RANGE_DURATION_MS).coerceAtMost(MAX_MILLISECONDS)
            .takeIf { it > start }
    }

    private fun displayEnd(start: Long, end: Long): Long? {
        val playableEnd = normalizeEnd(start, end) ?: return null
        if (playableEnd / 1_000 > start / 1_000) return playableEnd
        return ((start / 1_000 + 1) * 1_000)
            .coerceAtMost(MAX_MILLISECONDS)
            .takeIf { it > start }
    }

    private const val MAX_SECONDS = 7L * 24 * 60 * 60
    private const val MAX_MILLISECONDS = MAX_SECONDS * 1_000
    private const val POINT_RANGE_DURATION_MS = 1_000L
}
