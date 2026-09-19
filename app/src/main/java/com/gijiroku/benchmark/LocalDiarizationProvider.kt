package com.gijiroku.benchmark

/** Adapter exposing the existing CAM++ embedding and clustering implementation as a provider. */
class LocalDiarizationProvider(
    private val modelPath: String,
    private val appContext: android.content.Context? = null
) :
    PipelineProvider<DiarizationRequest, DiarizationArtifact> {

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.LOCAL_CAMPPLUS_DIARIZATION,
        displayName = AppLanguage.text("端末内 CAM++ 話者分離", "On-device CAM++ speaker separation"),
        stage = PipelineStage.DIARIZATION,
        executionMode = ExecutionMode.LOCAL,
        // TRANSCRIPT is temporary: the current lightweight implementation uses Whisper speech
        // regions instead of an independent VAD/segmentation model.
        dataRequired = setOf(DataKind.AUDIO, DataKind.TRANSCRIPT)
    )

    override suspend fun execute(
        input: DiarizationRequest,
        context: ExecutionContext
    ): ProviderResult<DiarizationArtifact> {
        val startedNanos = System.nanoTime()
        val windows = input.speechRegions.flatMap { segment ->
            makeWindows(segment.startMs, segment.endMs)
        }

        val embeddings = executeWithModelGate {
            SpeakerEmbeddingExtractor(modelPath).use { extractor ->
                windows.mapIndexed { index, (startMs, endMs) ->
                    if (index % RESOURCE_CHECK_INTERVAL_WINDOWS == 0) {
                        appContext?.let {
                            LocalModelRuntimeCoordinator.requireSafeToContinue(
                                it,
                                LocalModelKind.SPEAKER_EMBEDDING
                            )
                        }
                    }
                    val slice = sliceSamples(input.audio.samples, startMs, endMs, input.audio.sampleRate)
                    try {
                        if (slice.isEmpty()) null else extractor.embed(slice)
                    } finally {
                        slice.fill(0f)
                    }
                }
            }
        }
        val clustering = SpeakerClusterer.cluster(embeddings, input.numSpeakers)
        val speakerSegments = windows.mapIndexed { index, (startMs, endMs) ->
            SpeakerSegment(
                startMs = startMs,
                endMs = endMs,
                speakerLabel = SpeakerClusterer.labelName(clustering.clusterIds[index])
            )
        }
        val durationMs = (System.nanoTime() - startedNanos) / 1_000_000
        return ProviderResult(
            output = DiarizationArtifact(
                segments = speakerSegments,
                speakerCount = clustering.speakerCount,
                isSpeakerCountSpecified = clustering.isSpeakerCountSpecified,
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
        LocalModelRuntimeCoordinator.withExclusiveModel(it, LocalModelKind.SPEAKER_EMBEDDING, block)
    } ?: block()

    private fun makeWindows(start: Long, end: Long): List<Pair<Long, Long>> {
        val duration = end - start
        if (duration <= WINDOW_MS) return listOf(start to end)

        val windows = mutableListOf<Pair<Long, Long>>()
        var cursor = start
        while (end - cursor > WINDOW_MS) {
            windows.add(cursor to (cursor + WINDOW_MS))
            cursor += WINDOW_MS
        }
        val remainder = end - cursor
        if (remainder < MIN_WINDOW_MS && windows.isNotEmpty()) {
            val (lastStart, _) = windows.removeAt(windows.lastIndex)
            windows.add(lastStart to end)
        } else {
            windows.add(cursor to end)
        }
        return windows
    }

    private fun sliceSamples(
        samples: FloatArray,
        startMs: Long,
        endMs: Long,
        sampleRate: Int
    ): FloatArray {
        val startIndex = (startMs * sampleRate / 1000).toInt().coerceIn(0, samples.size)
        val endIndex = (endMs * sampleRate / 1000).toInt().coerceIn(startIndex, samples.size)
        return samples.copyOfRange(startIndex, endIndex)
    }

    companion object {
        private const val WINDOW_MS = 1500L
        private const val MIN_WINDOW_MS = 500L
        private const val RESOURCE_CHECK_INTERVAL_WINDOWS = 20
    }
}
