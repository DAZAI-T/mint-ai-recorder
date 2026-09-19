package com.gijiroku.benchmark

/** Baseline preprocessor that preserves the original recording byte-for-byte. */
class LocalNoOpAudioProvider :
    PipelineProvider<AudioPreprocessingRequest, AudioPreprocessingArtifact> {

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.LOCAL_NOOP_AUDIO,
        displayName = AppLanguage.text("処理なし（ローカル）", "No processing (local)"),
        stage = PipelineStage.AUDIO_PREPROCESSING,
        executionMode = ExecutionMode.LOCAL,
        dataRequired = setOf(DataKind.AUDIO)
    )

    override suspend fun execute(
        input: AudioPreprocessingRequest,
        context: ExecutionContext
    ): ProviderResult<AudioPreprocessingArtifact> {
        val startedAt = System.currentTimeMillis()
        val provenance = ArtifactProvenance(
            providerId = descriptor.id,
            createdAtEpochMs = System.currentTimeMillis(),
            inputHash = context.inputHash
        )
        return ProviderResult(
            output = AudioPreprocessingArtifact(
                audio = input.audio.copy(provenance = provenance),
                silenceRegions = emptyList(),
                provenance = provenance
            ),
            providerId = descriptor.id,
            durationMs = System.currentTimeMillis() - startedAt
        )
    }
}
