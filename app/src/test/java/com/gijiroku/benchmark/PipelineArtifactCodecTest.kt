package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineArtifactCodecTest {

    private val provenance = ArtifactProvenance("provider", 123, "input-hash")

    @Test
    fun transcriptAndDiarizationRoundTrip() {
        val transcript = TranscriptArtifact(
            listOf(TranscriptSegment(10, 20, "秘密の発話")),
            provenance
        )
        val diarization = DiarizationArtifact(
            listOf(SpeakerSegment(10, 20, "話者A")),
            speakerCount = 1,
            isSpeakerCountSpecified = false,
            provenance = provenance
        )

        assertEquals(transcript, PipelineArtifactCodec.decodeTranscript(PipelineArtifactCodec.encodeTranscript(transcript)))
        assertEquals(diarization, PipelineArtifactCodec.decodeDiarization(PipelineArtifactCodec.encodeDiarization(diarization)))
    }

    @Test
    fun documentAndSummaryRoundTrip() {
        val document = TextDocumentArtifact("整形済み本文", provenance)
        val summary = MeetingSummaryArtifact(
            purpose = "方針確認",
            decisions = listOf("採用する"),
            actionItems = listOf(ActionItem(null, "金曜", "資料を作る")),
            openQuestions = listOf("担当者"),
            summary = "会議要約",
            provenance = provenance,
            evidence = listOf(
                SummaryEvidence("decisions", 0, listOf("t000001"), listOf(TimeRange(10, 20)))
            ),
            title = "設計会議",
            topics = listOf(MeetingTopic("公開方法", "方式を比較した", listOf("話者A"))),
            importantInformation = listOf("期限は金曜"),
            templateId = MinutesTemplateIds.BUSINESS_MEETING
        )

        assertEquals(document, PipelineArtifactCodec.decodeDocument(PipelineArtifactCodec.encodeDocument(document)))
        assertEquals(summary, PipelineArtifactCodec.decodeSummary(PipelineArtifactCodec.encodeSummary(summary)))
    }

    @Test
    fun decodesSummarySavedBeforeStructuredMinutesExpansion() {
        val legacy = """
            {"purpose":"確認","decisions":[],"actionItems":[],"openQuestions":[],"summary":"概要","provenance":{"providerId":"provider","createdAtEpochMs":123,"inputHash":"input-hash"}}
        """.trimIndent()

        val decoded = PipelineArtifactCodec.decodeSummary(legacy)

        assertEquals("概要", decoded.summary)
        assertEquals(emptyList<MeetingTopic>(), decoded.topics)
        assertEquals(emptyList<String>(), decoded.importantInformation)
        assertEquals(MinutesTemplateIds.STANDARD_MEETING, decoded.templateId)
    }
}
