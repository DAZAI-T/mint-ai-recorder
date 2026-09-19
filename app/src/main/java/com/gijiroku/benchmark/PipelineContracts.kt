package com.gijiroku.benchmark

/** Replaceable processing steps in a recording pipeline. */
enum class PipelineStage {
    AUDIO_PREPROCESSING,
    DIARIZATION,
    TRANSCRIPTION,
    TEXT_FORMATTING,
    SUMMARIZATION
}

enum class ExecutionMode { LOCAL, CLOUD }

/** Data categories disclosed to the user before a cloud provider is executed. */
enum class DataKind {
    AUDIO,
    SILENCE_REGIONS,
    DIARIZATION,
    TRANSCRIPT,
    STRUCTURED_TRANSCRIPT,
    SUMMARY
}

data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val stage: PipelineStage,
    val executionMode: ExecutionMode,
    val dataRequired: Set<DataKind>,
    val destination: String? = null
)

data class ExecutionContext(
    val runId: String,
    val inputHash: String,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val presetId: String? = null
)

data class ArtifactProvenance(
    val providerId: String,
    val createdAtEpochMs: Long,
    val inputHash: String
)

data class ProviderResult<out O>(
    val output: O,
    val providerId: String,
    val durationMs: Long,
    val estimatedCostUsd: Double? = null,
    val destination: String? = null,
    /** Concrete model reported by a cloud API when an alias was used. */
    val modelId: String? = null
)

interface PipelineProvider<in I, out O> {
    val descriptor: ProviderDescriptor
    suspend fun execute(input: I, context: ExecutionContext): ProviderResult<O>
}

data class AudioArtifact(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationMs: Long,
    val sha256: String,
    val provenance: ArtifactProvenance
)

data class AudioPreprocessingRequest(val audio: AudioArtifact)

data class AudioPreprocessingArtifact(
    val audio: AudioArtifact,
    val silenceRegions: List<TimeRange>,
    val provenance: ArtifactProvenance
)

data class TimeRange(val startMs: Long, val endMs: Long)

data class TranscriptionRequest(
    val audio: AudioArtifact,
    val language: String = "ja",
    val nThreads: Int = 8
)

data class TranscriptArtifact(
    val segments: List<TranscriptSegment>,
    val provenance: ArtifactProvenance
)

data class DiarizationRequest(
    val audio: AudioArtifact,
    /** The current local implementation uses ASR speech regions instead of a separate VAD model. */
    val speechRegions: List<TranscriptSegment>,
    val numSpeakers: Int? = null
)

data class DiarizationArtifact(
    val segments: List<SpeakerSegment>,
    val speakerCount: Int,
    val isSpeakerCountSpecified: Boolean,
    val provenance: ArtifactProvenance
)

data class TextDocumentArtifact(
    val text: String,
    val provenance: ArtifactProvenance
)

data class TextFormattingRequest(
    val transcript: TranscriptArtifact,
    val diarization: DiarizationArtifact?,
    /** Local-only mapping. Cloud providers must replace these names with anonymous speaker IDs. */
    val speakerDisplayNames: Map<String, String> = emptyMap(),
    val language: String = "ja"
)

data class SummaryRequest(
    val document: TextDocumentArtifact,
    val language: String = "ja",
    val sourceTurns: List<SummarySourceTurn> = emptyList(),
    /** Local-only mapping. It must never be included in a cloud request. */
    val speakerDisplayNames: Map<String, String> = emptyMap(),
    val minutesTemplateId: String = MinutesTemplateIds.STANDARD_MEETING,
    /** User-authored instructions. Providers keep the fixed JSON schema and safety rules. */
    val customTemplateInstructions: String = ""
)

data class SummarySourceTurn(
    val turnId: String,
    val startMs: Long,
    val endMs: Long,
    val speakerLabel: String?,
    val text: String
)

data class MeetingSummaryArtifact(
    val purpose: String,
    val decisions: List<String>,
    val actionItems: List<ActionItem>,
    val openQuestions: List<String>,
    val summary: String,
    val provenance: ArtifactProvenance,
    val evidence: List<SummaryEvidence> = emptyList(),
    val title: String = "",
    val topics: List<MeetingTopic> = emptyList(),
    val importantInformation: List<String> = emptyList(),
    val templateId: String = MinutesTemplateIds.STANDARD_MEETING
)

data class MeetingTopic(
    val topic: String,
    val discussion: String,
    val speakers: List<String> = emptyList()
)

data class SummaryEvidence(
    val field: String,
    val itemIndex: Int?,
    val sourceTurnIds: List<String>,
    val sourceRanges: List<TimeRange>
)

data class ActionItem(
    val owner: String?,
    val dueDate: String?,
    val description: String
)

object ProviderIds {
    const val LOCAL_WHISPER = "local.whisper"
    const val OPENAI_WHISPER = "cloud.openai.whisper"
    const val OPENAI_RESPONSES_FORMATTING = "cloud.openai.responses.formatting"
    const val LOCAL_CAMPPLUS_DIARIZATION = "local.campplus"
    const val LOCAL_NOOP_AUDIO = "local.noop.audio"
    const val LOCAL_NOOP_TEXT = "local.noop.text"
    const val LOCAL_QWEN3_SUMMARY = "local.qwen3.summary"
    const val OPENAI_RESPONSES_SUMMARY = "cloud.openai.responses.summary"
    const val GEMINI_FLASH_LITE_TRANSCRIPTION = "cloud.google.gemini.flash_lite.transcription"
    const val GEMINI_FLASH_LITE_FORMATTING = "cloud.google.gemini.flash_lite.formatting"
    const val GEMINI_FLASH_LITE_SUMMARY = "cloud.google.gemini.flash_lite.summary"
}
