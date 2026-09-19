package com.gijiroku.benchmark

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MeetingExportDocument(
    val title: String,
    val createdAtLabel: String,
    val speakerCountLabel: String?,
    val summary: ExportSummary?,
    val transcript: String
) {
    fun markdown(): String = buildString {
        append("# ").append(title).append("\n\n")
        append(AppLanguage.text("- 作成日時: ", "- Created: ")).append(createdAtLabel).append('\n')
        speakerCountLabel?.let { append("- ").append(it).append('\n') }
        summary?.let { value ->
            append(AppLanguage.text("\n## 要約\n\n", "\n## Summary\n\n"))
            if (value.summary.isNotBlank()) append(value.summary.trim()).append("\n\n")
            if (value.purpose.isNotBlank()) {
                append(AppLanguage.text("### 目的\n\n", "### Purpose\n\n")).append(value.purpose.trim()).append("\n\n")
            }
            if (value.topics.isNotEmpty()) {
                append(AppLanguage.text("### 主な議題と議論\n\n", "### Topics and discussion\n\n"))
                value.topics.forEach { topic ->
                    append("- **").append(topic.topic.trim()).append("**")
                    if (topic.discussion.isNotBlank()) append(": ").append(topic.discussion.trim())
                    if (topic.speakers.isNotEmpty()) {
                        append(AppLanguage.text("（話者: ", " (Speakers: ")).append(topic.speakers.joinToString("、")).append("）")
                    }
                    append('\n')
                }
                append('\n')
            }
            appendListSection(AppLanguage.text("決定事項", "Decisions"), value.decisions)
            if (value.actionItems.isNotEmpty()) {
                append(AppLanguage.text("### アクション項目\n\n", "### Action items\n\n"))
                value.actionItems.forEach { item ->
                    append("- ").append(item.description.trim())
                    val details = listOfNotNull(
                        item.owner?.takeIf(String::isNotBlank)?.let { AppLanguage.text("担当: $it", "Owner: $it") },
                        item.dueDate?.takeIf(String::isNotBlank)?.let { AppLanguage.text("期限: $it", "Due: $it") }
                    )
                    if (details.isNotEmpty()) append("（").append(details.joinToString("、")).append("）")
                    append('\n')
                }
                append('\n')
            }
            appendListSection(AppLanguage.text("未解決事項", "Open questions"), value.openQuestions)
            appendListSection(AppLanguage.text("重要情報", "Important information"), value.importantInformation)
        }
        append(AppLanguage.text("\n## 文字起こし\n\n", "\n## Transcript\n\n"))
        append(transcript.trim().ifBlank { AppLanguage.text("（文字起こしはありません）", "(No transcript)") }).append('\n')
    }

    fun plainText(): String = markdown()
        .lineSequence()
        .joinToString("\n") { line ->
            when {
                line.startsWith("### ") -> line.removePrefix("### ")
                line.startsWith("## ") -> line.removePrefix("## ")
                line.startsWith("# ") -> line.removePrefix("# ")
                else -> line
            }
        }

    fun writeMarkdown(output: OutputStream) {
        val bytes = markdown().toByteArray(Charsets.UTF_8)
        try {
            output.write(bytes)
            output.flush()
        } finally {
            bytes.fill(0)
        }
    }

    private fun StringBuilder.appendListSection(label: String, values: List<String>) {
        if (values.isEmpty()) return
        append("### ").append(label).append("\n\n")
        values.forEach { append("- ").append(it.trim()).append('\n') }
        append('\n')
    }
}

data class ExportSummary(
    val purpose: String,
    val decisions: List<String>,
    val actionItems: List<ActionItem>,
    val openQuestions: List<String>,
    val summary: String,
    val topics: List<MeetingTopic> = emptyList(),
    val importantInformation: List<String> = emptyList()
)

object MeetingExportDocumentBuilder {
    fun build(
        metadata: EncryptedMeetingMetadata,
        currentRevisions: List<ArtifactRevisionRecord>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): MeetingExportDocument {
        val byArtifact = currentRevisions.associateBy(ArtifactRevisionRecord::artifactId)
        val diarization = byArtifact[ARTIFACT_DIARIZATION]?.let { revision ->
            runCatching { PipelineArtifactCodec.decodeDiarization(revision.content) }.getOrNull()
        }
        val summary = byArtifact[ARTIFACT_SUMMARY]?.let { revision ->
            runCatching { PipelineArtifactCodec.decodeSummary(revision.content) }.getOrNull()
        }
        val transcriptRevision = byArtifact[ARTIFACT_TRANSCRIPT]
        val transcriptArtifact = transcriptRevision?.let { revision ->
            runCatching { PipelineArtifactCodec.decodeTranscript(revision.content) }.getOrNull()
        }
        val formatted = byArtifact[ARTIFACT_FORMATTED]?.let { revision ->
            runCatching { PipelineArtifactCodec.decodeDocument(revision.content).text }
                .getOrElse { revision.content }
                .let { text ->
                    TranscriptTimestampParser.restoreTrustedRanges(
                        text,
                        transcriptArtifact?.segments.orEmpty().map { TimeRange(it.startMs, it.endMs) }
                    )
                }
        }
        val transcript = formatted ?: transcriptArtifact?.segments?.joinToString("\n") { segment ->
                    "${TranscriptTimestampParser.formatRange(segment.startMs, segment.endMs)} ${segment.text}"
                } ?: transcriptRevision?.content.orEmpty()
        val speakerCountLabel = diarization?.let {
            val kind = if (it.isSpeakerCountSpecified) AppLanguage.text("指定話者数", "Specified speakers") else AppLanguage.text("推定話者数", "Estimated speakers")
            AppLanguage.text("$kind: ${it.speakerCount}人", "$kind: ${it.speakerCount}")
        }
        return MeetingExportDocument(
            title = summary?.title?.takeIf(String::isNotBlank) ?: AppLanguage.text("議事録", "Minutes"),
            createdAtLabel = DATE_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(metadata.createdAtEpochMs)),
            speakerCountLabel = speakerCountLabel,
            summary = summary?.let {
                ExportSummary(
                    it.purpose,
                    it.decisions,
                    it.actionItems,
                    it.openQuestions,
                    it.summary,
                    it.topics,
                    it.importantInformation
                )
            },
            transcript = transcript
        )
    }

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
    private const val ARTIFACT_TRANSCRIPT = "transcript"
    private const val ARTIFACT_DIARIZATION = "diarization"
    private const val ARTIFACT_FORMATTED = "formatted-text"
    private const val ARTIFACT_SUMMARY = "summary"
}
