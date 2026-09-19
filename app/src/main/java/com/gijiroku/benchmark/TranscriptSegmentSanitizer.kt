package com.gijiroku.benchmark

/** Repairs invalid ASR time ranges before they are used for speaker separation. */
object TranscriptSegmentSanitizer {
    fun sanitize(segments: List<TranscriptSegment>, audioDurationMs: Long): List<TranscriptSegment> {
        val duration = audioDurationMs.coerceAtLeast(0)
        if (duration == 0L) return segments.filter { it.endMs > it.startMs }

        return buildList {
            segments.forEachIndexed { index, segment ->
                var start = segment.startMs.coerceIn(0, duration)
                var end = segment.endMs.coerceIn(start, duration)
                if (end == start) {
                    if (start == duration) {
                        start = (duration - FALLBACK_DURATION_MS).coerceAtLeast(0)
                        end = duration
                    } else {
                        val nextStart = segments.asSequence()
                            .drop(index + 1)
                            .map { it.startMs.coerceIn(0, duration) }
                            .firstOrNull { it > start }
                        end = minOf(
                            duration,
                            start + FALLBACK_DURATION_MS,
                            nextStart ?: Long.MAX_VALUE
                        )
                    }
                }
                if (end > start) add(segment.copy(startMs = start, endMs = end))
            }
        }
    }

    private const val FALLBACK_DURATION_MS = 1_500L
}
