package com.gijiroku.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MeetingExportDocumentTest {

    @Test
    fun buildsReadableMarkdownFromCurrentPipelineArtifacts() {
        val meetingId = "12345678-1234-4234-8234-123456789abc"
        val provenance = ArtifactProvenance(ProviderIds.LOCAL_WHISPER, 1, "hash")
        val diarization = DiarizationArtifact(
            segments = listOf(SpeakerSegment(0, 2_000, "話者A")),
            speakerCount = 2,
            isSpeakerCountSpecified = false,
            provenance = provenance
        )
        val summary = MeetingSummaryArtifact(
            purpose = "次期計画の確認",
            decisions = listOf("ローカル優先で進める"),
            actionItems = listOf(ActionItem("田中", "金曜日", "試作版を確認する")),
            openQuestions = listOf("配布日を決める"),
            summary = "設計方針を確認した。",
            provenance = provenance,
            title = "次期計画会議",
            topics = listOf(MeetingTopic("公開方式", "ローカル優先案を検討", listOf("話者A"))),
            importantInformation = listOf("金曜日に再確認")
        )
        val revisions = listOf(
            revision(meetingId, "diarization", PipelineArtifactCodec.encodeDiarization(diarization)),
            revision(
                meetingId,
                "formatted-text",
                PipelineArtifactCodec.encodeDocument(TextDocumentArtifact("話者A: おはようございます。", provenance))
            ),
            revision(meetingId, "summary", PipelineArtifactCodec.encodeSummary(summary))
        )

        val markdown = MeetingExportDocumentBuilder.build(
            metadata = EncryptedMeetingMetadata(meetingId, 0, AudioState.FINALIZED, 2_000),
            currentRevisions = revisions,
            zoneId = ZoneId.of("UTC")
        ).markdown()

        assertTrue(markdown.contains("# 次期計画会議"))
        assertTrue(markdown.contains("作成日時: 1970-01-01 00:00:00 UTC"))
        assertTrue(markdown.contains("推定話者数: 2人"))
        assertTrue(markdown.contains("## 要約"))
        assertTrue(markdown.contains("- ローカル優先で進める"))
        assertTrue(markdown.contains("公開方式"))
        assertTrue(markdown.contains("金曜日に再確認"))
        assertTrue(markdown.contains("試作版を確認する（担当: 田中、期限: 金曜日）"))
        assertTrue(markdown.contains("## 文字起こし"))
        assertTrue(markdown.contains("話者A: おはようございます。"))
        assertFalse(markdown.contains("\"provenance\""))
    }

    @Test
    fun fallsBackToTimestampedTranscriptWhenFormattedTextIsMissing() {
        val meetingId = "12345678-1234-4234-8234-123456789abc"
        val provenance = ArtifactProvenance(ProviderIds.LOCAL_WHISPER, 1, "hash")
        val transcript = TranscriptArtifact(
            listOf(TranscriptSegment(65_000, 67_500, "発言内容")),
            provenance
        )

        val markdown = MeetingExportDocumentBuilder.build(
            EncryptedMeetingMetadata(meetingId, 0, AudioState.DELETED, 67_500),
            listOf(revision(meetingId, "transcript", PipelineArtifactCodec.encodeTranscript(transcript))),
            ZoneId.of("UTC")
        ).markdown()

        assertTrue(markdown.contains("[01:05 - 01:07] 発言内容"))
    }

    @Test
    fun normalizesLegacyMillisecondRangesInFormattedText() {
        val meetingId = "12345678-1234-4234-8234-123456789abc"
        val provenance = ArtifactProvenance(ProviderIds.GEMINI_FLASH_LITE_FORMATTING, 1, "hash")
        val formatted = "[1130-2550] SPEAKER_A: an iPod\n[321529-326709] SPEAKER_A: a phone"

        val document = MeetingExportDocumentBuilder.build(
            EncryptedMeetingMetadata(meetingId, 0, AudioState.FINALIZED, 326_709),
            listOf(
                revision(
                    meetingId,
                    "formatted-text",
                    PipelineArtifactCodec.encodeDocument(TextDocumentArtifact(formatted, provenance))
                )
            ),
            ZoneId.of("UTC")
        )

        assertTrue(document.transcript.contains("[00:01 - 00:02] SPEAKER_A: an iPod"))
        assertTrue(document.transcript.contains("[05:21 - 05:26] SPEAKER_A: a phone"))
        assertFalse(document.transcript.contains("[1130-2550]"))
    }

    @Test
    fun restoresExportTimestampsFromTranscriptArtifact() {
        val meetingId = "12345678-1234-4234-8234-123456789abc"
        val provenance = ArtifactProvenance(ProviderIds.GEMINI_FLASH_LITE_FORMATTING, 1, "hash")
        val transcript = TranscriptArtifact(
            listOf(
                TranscriptSegment(31_839, 32_679, "widescreen"),
                TranscriptSegment(791_009, 794_549, "call")
            ),
            provenance
        )
        val formatted = "[00:31 - 05:26] SPEAKER_A: widescreen\n[791009-791009] SPEAKER_B: call"

        val document = MeetingExportDocumentBuilder.build(
            EncryptedMeetingMetadata(meetingId, 0, AudioState.FINALIZED, 794_549),
            listOf(
                revision(meetingId, "transcript", PipelineArtifactCodec.encodeTranscript(transcript)),
                revision(
                    meetingId,
                    "formatted-text",
                    PipelineArtifactCodec.encodeDocument(TextDocumentArtifact(formatted, provenance))
                )
            ),
            ZoneId.of("UTC")
        )

        assertEquals(
            "[00:31 - 00:32] SPEAKER_A: widescreen\n[13:11 - 13:14] SPEAKER_B: call",
            document.transcript
        )
    }

    private fun revision(meetingId: String, artifactId: String, content: String) = ArtifactRevisionRecord(
        revisionId = "$artifactId-revision",
        meetingId = meetingId,
        artifactId = artifactId,
        parentRevisionId = null,
        revisionKind = RevisionKind.AI_GENERATED,
        createdAtEpochMs = 1,
        contentHash = ArtifactHasher.sha256(content),
        inputRevisionIds = emptyList(),
        providerRunId = null,
        providerId = null,
        modelId = null,
        promptVersion = null,
        author = "AI",
        sourceRanges = emptyList(),
        cacheKey = null,
        content = content
    )
}
