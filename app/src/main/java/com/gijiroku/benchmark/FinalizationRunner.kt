package com.gijiroku.benchmark

import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Runs the selected post-recording providers and merges their typed artifacts for the current UI.
 * The synchronous facade is retained because RecordActivity already invokes it on a worker thread.
 */
enum class FinalizationStep {
    AUDIO_LOADING,
    AUDIO_PREPROCESSING,
    TRANSCRIPTION,
    DIARIZATION,
    TEXT_FORMATTING,
    SUMMARIZATION,
    COMPLETED
}

data class FinalizationProgress(val step: FinalizationStep, val label: String, val completed: Int, val total: Int)

class FinalizationRunner(private val modelStore: ModelStore) {

    fun run(
        audioSource: AudioSource,
        numSpeakers: Int? = null,
        onProgress: (FinalizationProgress) -> Unit = {}
    ): String = runBlocking {
        runPipeline(audioSource, numSpeakers, onProgress)
    }

    private suspend fun runPipeline(
        audioSource: AudioSource,
        numSpeakers: Int?,
        onProgress: (FinalizationProgress) -> Unit
    ): String {
        val language = AppLanguage.code
        onProgress(FinalizationProgress(FinalizationStep.AUDIO_LOADING, AppLanguage.text("録音を安全に読み込み中", "Securely loading recording"), 0, 6))
        val loaded = audioSource.load()
        var processedSamples: FloatArray? = null
        val database = EncryptedMeetingDatabase(modelStore.appContext)
        val audio = AudioArtifact(
            samples = loaded.samples,
            sampleRate = loaded.sampleRate,
            durationMs = loaded.durationMs,
            sha256 = loaded.sha256,
            provenance = ArtifactProvenance(
                providerId = "recording.microphone",
                createdAtEpochMs = audioSource.createdAtEpochMs,
                inputHash = loaded.sha256
            )
        )

        try {
            database.upsertMeeting(
                EncryptedMeetingMetadata(
                    meetingId = audioSource.stableId,
                    createdAtEpochMs = audioSource.createdAtEpochMs,
                    audioState = if (loaded.finalized) AudioState.FINALIZED else AudioState.RECORDING,
                    durationMs = loaded.durationMs
                )
            )
            val runId = UUID.randomUUID().toString()
            val preferences = ProviderPreferences(modelStore.appContext)
            val templateSettings = MinutesTemplateSettings(modelStore.appContext)
            val presetId: String? = null
            val registry = ProviderRegistry(modelStore)
            val executor = ProviderExecutor(
                ExecutionHistoryStore(modelStore.appContext, audioSource.stableId, database)
            )

            onProgress(FinalizationProgress(FinalizationStep.AUDIO_PREPROCESSING, AppLanguage.text("音声を前処理中", "Preprocessing audio"), 1, 6))
            val preprocessed = executor.execute(
                provider = registry.audioPreprocessing(
                    preferences.selectedProvider(PipelineStage.AUDIO_PREPROCESSING)
                ),
                input = AudioPreprocessingRequest(audio),
                context = ExecutionContext(runId = runId, inputHash = loaded.sha256, presetId = presetId)
            ).output
            processedSamples = preprocessed.audio.samples

            onProgress(FinalizationProgress(FinalizationStep.TRANSCRIPTION, AppLanguage.text("音声を文字起こし中", "Transcribing audio"), 2, 6))
            val transcriptionProvider = registry.transcription(
                preferences.selectedProvider(PipelineStage.TRANSCRIPTION)
            )
            val transcriptionCacheKey = stageCacheKey(
                "transcription-v1",
                transcriptionProvider.descriptor.id,
                preprocessed.audio.sha256,
                language,
                FINAL_THREADS.toString()
            )
            val currentTranscript = database.currentRevision(audioSource.stableId, ARTIFACT_TRANSCRIPT)
            val (transcriptionRevision, transcription) = if (
                currentTranscript?.cacheKey == transcriptionCacheKey
            ) {
                val decoded = PipelineArtifactCodec.decodeTranscript(currentTranscript.content)
                currentTranscript to decoded.copy(
                    segments = TranscriptSegmentSanitizer.sanitize(
                        decoded.segments,
                        preprocessed.audio.durationMs
                    )
                )
            } else {
                val rawGenerated = executor.execute(
                    provider = transcriptionProvider,
                    input = TranscriptionRequest(audio = preprocessed.audio, language = language, nThreads = FINAL_THREADS),
                    context = ExecutionContext(runId = runId, inputHash = preprocessed.audio.sha256, presetId = presetId)
                ).output
                val generated = rawGenerated.copy(
                    segments = TranscriptSegmentSanitizer.sanitize(
                        rawGenerated.segments,
                        preprocessed.audio.durationMs
                    )
                )
                val revision = database.appendRevision(
                    audioSource.stableId,
                    ArtifactRevisionDraft(
                        artifactId = ARTIFACT_TRANSCRIPT,
                        content = PipelineArtifactCodec.encodeTranscript(generated),
                        revisionKind = RevisionKind.AI_GENERATED,
                        providerRunId = runId,
                        providerId = transcriptionProvider.descriptor.id,
                        author = "AI",
                        sourceRanges = generated.segments.map {
                            ArtifactSourceRange(it.startMs, it.endMs)
                        },
                        cacheKey = transcriptionCacheKey
                    )
                )
                revision to generated
            }

            onProgress(FinalizationProgress(FinalizationStep.DIARIZATION, AppLanguage.text("話者を分離中", "Separating speakers"), 3, 6))
            val diarizationResult = if (modelStore.hasSpeakerModel()) {
                val diarizationProvider = registry.diarization(
                    preferences.selectedProvider(PipelineStage.DIARIZATION)
                )
                val diarizationInputHash = ArtifactHasher.sha256(buildString {
                    append(preprocessed.audio.sha256).append(':').append(numSpeakers ?: "auto")
                    transcription.segments.forEach {
                        append(':').append(it.startMs).append(':').append(it.endMs).append(':').append(it.text)
                    }
                })
                val diarizationCacheKey = stageCacheKey(
                    "diarization-v2",
                    diarizationProvider.descriptor.id,
                    diarizationInputHash
                )
                val currentDiarization = database.currentRevision(
                    audioSource.stableId,
                    ARTIFACT_DIARIZATION
                )
                if (currentDiarization?.cacheKey == diarizationCacheKey) {
                    currentDiarization to PipelineArtifactCodec.decodeDiarization(currentDiarization.content)
                } else {
                    val generated = executor.execute(
                        provider = diarizationProvider,
                        input = DiarizationRequest(
                            audio = preprocessed.audio,
                            speechRegions = transcription.segments,
                            numSpeakers = numSpeakers
                        ),
                        context = ExecutionContext(runId = runId, inputHash = diarizationInputHash, presetId = presetId)
                    ).output
                    val revision = database.appendRevision(
                        audioSource.stableId,
                        ArtifactRevisionDraft(
                            artifactId = ARTIFACT_DIARIZATION,
                            content = PipelineArtifactCodec.encodeDiarization(generated),
                            revisionKind = RevisionKind.AI_GENERATED,
                            inputRevisionIds = listOf(transcriptionRevision.revisionId),
                            providerRunId = runId,
                            providerId = diarizationProvider.descriptor.id,
                            author = "AI",
                            sourceRanges = generated.segments.map {
                                ArtifactSourceRange(it.startMs, it.endMs)
                            },
                            cacheKey = diarizationCacheKey
                        )
                    )
                    revision to generated
                }
            } else {
                null
            }
            val diarizationRevision = diarizationResult?.first
            val diarization = diarizationResult?.second

            onProgress(FinalizationProgress(FinalizationStep.TEXT_FORMATTING, AppLanguage.text("文字起こしを整形中", "Formatting transcript"), 4, 6))
            val formattingHash = ArtifactHasher.sha256(
                buildString {
                    transcription.segments.forEach { append(it.startMs).append(':').append(it.endMs).append(':').append(it.text) }
                    diarization?.segments?.forEach { append(it.startMs).append(':').append(it.endMs).append(':').append(it.speakerLabel) }
                }
            )
            val formattingProvider = registry.textFormatting(
                preferences.selectedProvider(PipelineStage.TEXT_FORMATTING)
            )
            val formattingCacheKey = stageCacheKey(
                "formatting-v3", language,
                formattingProvider.descriptor.id,
                formattingHash,
                transcriptionRevision.revisionId,
                diarizationRevision?.revisionId.orEmpty()
            )
            val currentDocument = database.currentRevision(audioSource.stableId, ARTIFACT_FORMATTED)
            val (documentRevision, document) = if (currentDocument?.cacheKey == formattingCacheKey) {
                currentDocument to PipelineArtifactCodec.decodeDocument(currentDocument.content)
            } else {
                val generated = executor.execute(
                    provider = formattingProvider,
                    input = TextFormattingRequest(transcription, diarization, language = language),
                    context = ExecutionContext(runId = runId, inputHash = formattingHash, presetId = presetId)
                ).output
                val inputs = listOfNotNull(
                    transcriptionRevision.revisionId,
                    diarizationRevision?.revisionId
                )
                val revision = database.appendRevision(
                    audioSource.stableId,
                    ArtifactRevisionDraft(
                        artifactId = ARTIFACT_FORMATTED,
                        content = PipelineArtifactCodec.encodeDocument(generated),
                        revisionKind = RevisionKind.AI_GENERATED,
                        inputRevisionIds = inputs,
                        providerRunId = runId,
                        providerId = formattingProvider.descriptor.id,
                        promptVersion = when (formattingProvider.descriptor.id) {
                            ProviderIds.LOCAL_NOOP_TEXT -> "local-basic-v1"
                            ProviderIds.OPENAI_RESPONSES_FORMATTING -> OpenAiTextFormattingProvider.PROMPT_VERSION
                            ProviderIds.GEMINI_FLASH_LITE_FORMATTING -> "gemini-flash-lite-formatting-v1"
                            else -> null
                        },
                        author = "AI",
                        sourceRanges = transcription.segments.map {
                            ArtifactSourceRange(it.startMs, it.endMs)
                        },
                        cacheKey = formattingCacheKey
                    )
                )
                revision to generated
            }

            onProgress(FinalizationProgress(FinalizationStep.SUMMARIZATION, AppLanguage.text("会議を要約中", "Summarizing meeting"), 5, 6))
            val summaryProviderId = preferences.selectedProvider(PipelineStage.SUMMARIZATION)
            val summary = summaryProviderId?.let {
                val provider = registry.summarization(it)
                val summaryTurns = TranscriptSpeakerMerger.merge(
                    transcription.segments,
                    diarization?.segments ?: emptyList()
                ).mapIndexed { index, utterance ->
                    SummarySourceTurn(
                        turnId = "t${(index + 1).toString().padStart(6, '0')}",
                        startMs = utterance.startMs,
                        endMs = utterance.endMs,
                        speakerLabel = utterance.speakerLabel,
                        text = utterance.text
                    )
                }
                val summaryInputHash = ArtifactHasher.sha256(document.text)
                val templateId = templateSettings.selectedTemplateId
                val customInstructions = if (templateId == MinutesTemplateIds.CUSTOM) {
                    templateSettings.customInstructions
                } else ""
                val summaryCacheKey = stageCacheKey(
                    "summary-v5", language,
                    provider.descriptor.id,
                    summaryInputHash,
                    documentRevision.revisionId,
                    templateId,
                    ArtifactHasher.sha256(customInstructions)
                )
                val currentSummary = database.currentRevision(audioSource.stableId, ARTIFACT_SUMMARY)
                if (currentSummary?.cacheKey == summaryCacheKey) {
                    PipelineArtifactCodec.decodeSummary(currentSummary.content)
                } else {
                    val request = SummaryRequest(
                        language = language,
                        document = document,
                        sourceTurns = summaryTurns,
                        minutesTemplateId = templateId,
                        customTemplateInstructions = customInstructions
                    )
                    val executingProvider: PipelineProvider<SummaryRequest, MeetingSummaryArtifact> =
                        if (provider is LocalQwenSummaryProvider) {
                            object : PipelineProvider<SummaryRequest, MeetingSummaryArtifact> {
                                override val descriptor = provider.descriptor
                                override suspend fun execute(
                                    input: SummaryRequest,
                                    context: ExecutionContext
                                ): ProviderResult<MeetingSummaryArtifact> = provider.executeWithCheckpoints(
                                    input,
                                    context,
                                    DatabaseSummaryCheckpointStore(
                                        database = database,
                                        meetingId = audioSource.stableId,
                                        inputRevisionId = documentRevision.revisionId,
                                        runId = runId
                                    )
                                )
                            }
                        } else {
                            provider
                        }
                    val generated = executor.execute(
                        provider = executingProvider,
                        input = request,
                        context = ExecutionContext(runId = runId, inputHash = summaryInputHash, presetId = presetId)
                    ).output
                    database.appendRevision(
                        audioSource.stableId,
                        ArtifactRevisionDraft(
                            artifactId = ARTIFACT_SUMMARY,
                            content = PipelineArtifactCodec.encodeSummary(generated),
                            revisionKind = RevisionKind.AI_GENERATED,
                            inputRevisionIds = listOf(documentRevision.revisionId),
                            providerRunId = runId,
                            providerId = provider.descriptor.id,
                            promptVersion = when (provider.descriptor.id) {
                                ProviderIds.OPENAI_RESPONSES_SUMMARY -> OpenAiSummaryProvider.PROMPT_VERSION
                                ProviderIds.LOCAL_QWEN3_SUMMARY -> LocalQwenSummaryProvider.PROMPT_VERSION
                                ProviderIds.GEMINI_FLASH_LITE_SUMMARY -> GeminiFlashLiteSummaryProvider.PROMPT_VERSION
                                else -> null
                            },
                            author = "AI",
                            sourceRanges = generated.evidence.flatMap { it.sourceRanges }
                                .ifEmpty { summaryTurns.map { TimeRange(it.startMs, it.endMs) } }
                                .distinct()
                                .map { ArtifactSourceRange(it.startMs, it.endMs) },
                            cacheKey = summaryCacheKey
                        )
                    )
                    generated
                }
            }

            val result = if (summary == null) document.text else {
                document.text + "\n\n---\n\n" + formatSummary(summary)
            }
            onProgress(FinalizationProgress(FinalizationStep.COMPLETED, AppLanguage.text("確定処理が完了しました", "Processing completed"), 6, 6))
            return result
        } finally {
            if (processedSamples !== loaded.samples) processedSamples?.fill(0f)
            loaded.samples.fill(0f)
            database.close()
        }
    }

    private fun stageCacheKey(vararg values: String): String =
        ArtifactHasher.sha256(values.joinToString("\u001f"))

    private fun formatSummary(summary: MeetingSummaryArtifact): String = buildString {
        if (summary.title.isNotBlank()) {
            appendLine(summary.title)
            appendLine()
        }
        appendLine(AppLanguage.text("概要", "Overview"))
        appendLine(summary.summary.ifBlank { AppLanguage.text("未記載", "Not specified") } + evidenceLabel(summary, "summary", null))
        appendLine()
        appendLine(AppLanguage.text("会議の目的", "Meeting purpose"))
        appendLine(summary.purpose.ifBlank { AppLanguage.text("未記載", "Not specified") } + evidenceLabel(summary, "purpose", null))
        appendLine()
        appendLine(AppLanguage.text("主な議題・議論", "Topics and discussion"))
        if (summary.topics.isEmpty()) appendLine(AppLanguage.text("- なし", "- None")) else summary.topics.forEachIndexed { index, topic ->
            val speakers = topic.speakers.takeIf { it.isNotEmpty() }
                ?.joinToString("、", prefix = AppLanguage.text(" / 話者: ", " / Speakers: ")).orEmpty()
            appendLine("- ${topic.topic}: ${topic.discussion}$speakers${evidenceLabel(summary, "topics", index)}")
        }
        appendLine()
        appendLine(AppLanguage.text("決定事項", "Decisions"))
        appendSummaryList(summary, "decisions", summary.decisions)
        appendLine()
        appendLine(AppLanguage.text("アクション項目", "Action items"))
        if (summary.actionItems.isEmpty()) appendLine(AppLanguage.text("- なし", "- None")) else summary.actionItems.forEachIndexed { index, it ->
            appendLine(
                AppLanguage.text("- 担当: ${it.owner ?: "不明"} / 期限: ${it.dueDate ?: "不明"} / ${it.description}", "- Owner: ${it.owner ?: "Unknown"} / Due: ${it.dueDate ?: "Unknown"} / ${it.description}") +
                    evidenceLabel(summary, "action_items", index)
            )
        }
        appendLine()
        appendLine(AppLanguage.text("未解決事項", "Open questions"))
        appendSummaryList(summary, "open_questions", summary.openQuestions)
        appendLine()
        appendLine(AppLanguage.text("重要情報", "Important information"))
        appendSummaryList(summary, "important_information", summary.importantInformation)
    }

    private fun StringBuilder.appendSummaryList(
        summary: MeetingSummaryArtifact,
        field: String,
        items: List<String>
    ) {
        if (items.isEmpty()) appendLine(AppLanguage.text("- なし", "- None")) else items.forEachIndexed { index, item ->
            appendLine("- $item${evidenceLabel(summary, field, index)}")
        }
    }

    private fun evidenceLabel(summary: MeetingSummaryArtifact, field: String, itemIndex: Int?): String {
        val ranges = summary.evidence
            .filter { it.field == field && it.itemIndex == itemIndex }
            .flatMap { it.sourceRanges }
            .distinct()
        if (ranges.isEmpty()) return ""
        return ranges.joinToString(prefix = AppLanguage.text(" [根拠: ", " [Evidence: "), postfix = "]") { range ->
            "${formatSummaryTimestamp(range.startMs)}–${formatSummaryTimestamp(range.endMs)}"
        }
    }

    private fun formatSummaryTimestamp(ms: Long): String {
        val seconds = ms / 1000
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    companion object {
        private const val FINAL_THREADS = 8
        private const val ARTIFACT_TRANSCRIPT = "transcript"
        private const val ARTIFACT_DIARIZATION = "diarization"
        private const val ARTIFACT_FORMATTED = "formatted-text"
        private const val ARTIFACT_SUMMARY = "summary"
    }
}

private class DatabaseSummaryCheckpointStore(
    private val database: EncryptedMeetingDatabase,
    private val meetingId: String,
    private val inputRevisionId: String,
    private val runId: String
) : SummaryCheckpointStore {
    override fun load(checkpointKey: String): MeetingSummaryArtifact? {
        val revision = database.currentRevision(meetingId, artifactId(checkpointKey)) ?: return null
        if (revision.cacheKey != checkpointKey) return null
        return runCatching { PipelineArtifactCodec.decodeSummary(revision.content) }.getOrNull()
    }

    override fun save(
        checkpointKey: String,
        artifact: MeetingSummaryArtifact,
        sourceRanges: List<TimeRange>
    ) {
        database.appendRevision(
            meetingId,
            ArtifactRevisionDraft(
                artifactId = artifactId(checkpointKey),
                content = PipelineArtifactCodec.encodeSummary(artifact),
                revisionKind = RevisionKind.AI_GENERATED,
                inputRevisionIds = listOf(inputRevisionId),
                providerRunId = runId,
                providerId = ProviderIds.LOCAL_QWEN3_SUMMARY,
                modelId = OfficialModelCatalog.QWEN_SUMMARY_ID,
                promptVersion = LocalQwenSummaryProvider.PROMPT_VERSION,
                author = "AI",
                sourceRanges = sourceRanges.map { ArtifactSourceRange(it.startMs, it.endMs) },
                cacheKey = checkpointKey
            )
        )
    }

    private fun artifactId(checkpointKey: String): String = "qsum-${checkpointKey.take(32)}"
}
