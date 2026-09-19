package com.gijiroku.benchmark

/** 文字起こしの1発話区間（whisperのセグメント）。 */
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

/** Providerから返される話者分離の1区間。 */
data class SpeakerSegment(val startMs: Long, val endMs: Long, val speakerLabel: String)

/** 文字起こしと話者分離を時刻で結合した結果（design.md 3章「文章と話者を時刻で結合」）。 */
data class MergedUtterance(val startMs: Long, val endMs: Long, val speakerLabel: String?, val text: String)

/**
 * 文字起こしセグメントと話者分離セグメントを、区間の重なりが最大の話者に割り当てる形で結合する。
 * 話者分離結果が空の場合（Provider未設定など）は speakerLabel=null のまま返す —
 * 話者未確定を「話者A」のように決め打ちで埋めない（根拠のない推測で補わない、という設計方針に合わせる）。
 */
object TranscriptSpeakerMerger {

    fun merge(transcriptSegments: List<TranscriptSegment>, speakerSegments: List<SpeakerSegment>): List<MergedUtterance> {
        return transcriptSegments.map { seg ->
            MergedUtterance(
                startMs = seg.startMs,
                endMs = seg.endMs,
                speakerLabel = bestOverlappingSpeaker(seg, speakerSegments),
                text = seg.text
            )
        }
    }

    private fun bestOverlappingSpeaker(seg: TranscriptSegment, speakerSegments: List<SpeakerSegment>): String? {
        var bestLabel: String? = null
        var bestOverlapMs = 0L
        for (sp in speakerSegments) {
            val overlapMs = (minOf(seg.endMs, sp.endMs) - maxOf(seg.startMs, sp.startMs)).coerceAtLeast(0)
            if (overlapMs > bestOverlapMs) {
                bestOverlapMs = overlapMs
                bestLabel = sp.speakerLabel
            }
        }
        return bestLabel
    }
}
