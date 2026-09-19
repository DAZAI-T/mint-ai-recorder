package com.gijiroku.benchmark

/** Adapter exposing the existing whisper.cpp bridge through the common provider contract. */
class LocalWhisperProvider(
    private val modelPath: String,
    private val appContext: android.content.Context? = null
) :
    PipelineProvider<TranscriptionRequest, TranscriptArtifact> {

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.LOCAL_WHISPER,
        displayName = AppLanguage.text("端末内 Whisper", "On-device Whisper"),
        stage = PipelineStage.TRANSCRIPTION,
        executionMode = ExecutionMode.LOCAL,
        dataRequired = setOf(DataKind.AUDIO)
    )

    override suspend fun execute(
        input: TranscriptionRequest,
        context: ExecutionContext
    ): ProviderResult<TranscriptArtifact> {
        val startedNanos = System.nanoTime()
        val segments = executeWithModelGate {
            WhisperBridge().use { whisper ->
                whisper.load(modelPath)
                whisper.transcribeSegments(
                    samples = input.audio.samples,
                    nThreads = input.nThreads,
                    language = input.language
                )
            }
        }
        val durationMs = (System.nanoTime() - startedNanos) / 1_000_000
        return ProviderResult(
            output = TranscriptArtifact(
                segments = segments,
                provenance = ArtifactProvenance(
                    providerId = descriptor.id,
                    createdAtEpochMs = System.currentTimeMillis(),
                    inputHash = context.inputHash
                )
            ),
            providerId = descriptor.id,
            durationMs = durationMs
        )
    }

    private fun <T> executeWithModelGate(block: () -> T): T = appContext?.let {
        LocalModelRuntimeCoordinator.withExclusiveModel(it, LocalModelKind.FINAL_WHISPER, block)
    } ?: block()
}
