package com.gijiroku.benchmark

/** Deterministic formatter; it does not reinterpret or send transcript content. */
class LocalBasicTextFormatterProvider :
    PipelineProvider<TextFormattingRequest, TextDocumentArtifact> {

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.LOCAL_NOOP_TEXT,
        displayName = AppLanguage.text("基本整形（原文保持）", "Basic formatting (preserves source)"),
        stage = PipelineStage.TEXT_FORMATTING,
        executionMode = ExecutionMode.LOCAL,
        dataRequired = setOf(DataKind.TRANSCRIPT, DataKind.DIARIZATION)
    )

    override suspend fun execute(
        input: TextFormattingRequest,
        context: ExecutionContext
    ): ProviderResult<TextDocumentArtifact> {
        val startedAt = System.currentTimeMillis()
        val merged = TranscriptSpeakerMerger.merge(
            transcriptSegments = input.transcript.segments,
            speakerSegments = input.diarization?.segments ?: emptyList()
        )
        val speakerCountLine = input.diarization?.let {
            val kind = if (it.isSpeakerCountSpecified) AppLanguage.text("指定", "Specified") else AppLanguage.text("推定", "Estimated")
            AppLanguage.text("${kind}話者数: ${it.speakerCount}人", "${kind} speakers: ${it.speakerCount}")
        }
        val body = if (merged.isEmpty()) {
            AppLanguage.text("(発話が検出されませんでした)", "(No speech detected)")
        } else {
            merged.joinToString("\n") { utterance ->
                val speaker = utterance.speakerLabel ?: AppLanguage.text("話者未分離", "Speakers not separated")
                "${TranscriptTimestampParser.formatRange(utterance.startMs, utterance.endMs)} " +
                    "$speaker: ${utterance.text.trim()}"
            }
        }
        val document = listOfNotNull(speakerCountLine, body).joinToString("\n\n")
        return ProviderResult(
            output = TextDocumentArtifact(
                text = document,
                provenance = ArtifactProvenance(
                    providerId = descriptor.id,
                    createdAtEpochMs = System.currentTimeMillis(),
                    inputHash = context.inputHash
                )
            ),
            providerId = descriptor.id,
            durationMs = System.currentTimeMillis() - startedAt
        )
    }

}
