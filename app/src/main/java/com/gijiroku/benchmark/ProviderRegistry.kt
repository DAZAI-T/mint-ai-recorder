package com.gijiroku.benchmark

/** Typed provider lookup. Unknown selections fail explicitly; cloud fallback is never automatic. */
class ProviderRegistry(private val modelStore: ModelStore) {

    fun audioPreprocessing(
        providerId: String?
    ): PipelineProvider<AudioPreprocessingRequest, AudioPreprocessingArtifact> {
        return when (providerId) {
            ProviderIds.LOCAL_NOOP_AUDIO -> LocalNoOpAudioProvider()
            else -> error(AppLanguage.text("利用できない音声前処理Providerです: $providerId", "Unavailable audio preprocessing provider: $providerId"))
        }
    }

    fun transcription(providerId: String?): PipelineProvider<TranscriptionRequest, TranscriptArtifact> {
        return when (providerId) {
            ProviderIds.LOCAL_WHISPER -> {
                check(modelStore.hasFinalModel()) { AppLanguage.text("確定処理用Whisperモデルが未設定です", "No Whisper model selected for processing") }
                LocalWhisperProvider(modelStore.finalModelPath(), modelStore.appContext)
            }
            ProviderIds.OPENAI_WHISPER -> {
                val secretStore = SecretStore(modelStore.appContext)
                check(secretStore.hasSecret(SecretStore.OPENAI_API_KEY)) { AppLanguage.text("OpenAI APIキーが未設定です", "OpenAI API key is not configured") }
                val model = requireCloudModel(CloudModelProvider.OPENAI, PipelineStage.TRANSCRIPTION)
                OpenAiWhisperProvider(
                    secretStore = secretStore,
                    model = model
                )
            }
            ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION -> {
                val secretStore = SecretStore(modelStore.appContext)
                check(secretStore.hasSecret(SecretStore.GEMINI_API_KEY)) { AppLanguage.text("Gemini APIキーが未設定です", "Gemini API key is not configured") }
                GeminiFlashLiteTranscriptionProvider(
                    secretStore,
                    requireCloudModel(CloudModelProvider.GEMINI, PipelineStage.TRANSCRIPTION)
                )
            }
            else -> error(AppLanguage.text("利用できない音声認識Providerです: $providerId", "Unavailable transcription provider: $providerId"))
        }
    }

    fun diarization(providerId: String?): PipelineProvider<DiarizationRequest, DiarizationArtifact> {
        return when (providerId) {
            ProviderIds.LOCAL_CAMPPLUS_DIARIZATION -> {
                check(modelStore.hasSpeakerModel()) { AppLanguage.text("話者分離モデルが未設定です", "No speaker separation model selected") }
                LocalDiarizationProvider(modelStore.speakerModelPath(), modelStore.appContext)
            }
            else -> error(AppLanguage.text("利用できない話者分離Providerです: $providerId", "Unavailable speaker separation provider: $providerId"))
        }
    }

    fun textFormatting(
        providerId: String?
    ): PipelineProvider<TextFormattingRequest, TextDocumentArtifact> {
        return when (providerId) {
            ProviderIds.LOCAL_NOOP_TEXT -> LocalBasicTextFormatterProvider()
            ProviderIds.OPENAI_RESPONSES_FORMATTING -> {
                val secretStore = SecretStore(modelStore.appContext)
                check(secretStore.hasSecret(SecretStore.OPENAI_API_KEY)) { AppLanguage.text("OpenAI APIキーが未設定です", "OpenAI API key is not configured") }
                OpenAiTextFormattingProvider(
                    secretStore,
                    requireCloudModel(CloudModelProvider.OPENAI, PipelineStage.TEXT_FORMATTING)
                )
            }
            ProviderIds.GEMINI_FLASH_LITE_FORMATTING -> {
                val secretStore = SecretStore(modelStore.appContext)
                check(secretStore.hasSecret(SecretStore.GEMINI_API_KEY)) { AppLanguage.text("Gemini APIキーが未設定です", "Gemini API key is not configured") }
                GeminiFlashLiteTextFormattingProvider(
                    secretStore,
                    requireCloudModel(CloudModelProvider.GEMINI, PipelineStage.TEXT_FORMATTING)
                )
            }
            else -> error(AppLanguage.text("利用できないテキスト整形Providerです: $providerId", "Unavailable text formatting provider: $providerId"))
        }
    }

    fun summarization(providerId: String?): PipelineProvider<SummaryRequest, MeetingSummaryArtifact> {
        return when (providerId) {
            ProviderIds.LOCAL_QWEN3_SUMMARY -> {
                check(modelStore.hasQwenSummaryModel()) { AppLanguage.text("ローカルQwen3-4Bモデルが未設定です", "No local Qwen3-4B model selected") }
                LocalQwenSummaryProvider(modelStore.qwenSummaryModelPath(), modelStore.appContext)
            }
            ProviderIds.OPENAI_RESPONSES_SUMMARY -> {
                val secretStore = SecretStore(modelStore.appContext)
                check(secretStore.hasSecret(SecretStore.OPENAI_API_KEY)) { AppLanguage.text("OpenAI APIキーが未設定です", "OpenAI API key is not configured") }
                val model = requireCloudModel(CloudModelProvider.OPENAI, PipelineStage.SUMMARIZATION)
                OpenAiSummaryProvider(
                    secretStore = secretStore,
                    model = model
                )
            }
            ProviderIds.GEMINI_FLASH_LITE_SUMMARY -> {
                val secretStore = SecretStore(modelStore.appContext)
                check(secretStore.hasSecret(SecretStore.GEMINI_API_KEY)) { AppLanguage.text("Gemini APIキーが未設定です", "Gemini API key is not configured") }
                GeminiFlashLiteSummaryProvider(
                    secretStore,
                    requireCloudModel(CloudModelProvider.GEMINI, PipelineStage.SUMMARIZATION)
                )
            }
            else -> error(AppLanguage.text("利用できない要約Providerです: $providerId", "Unavailable summary provider: $providerId"))
        }
    }

    fun availableDescriptors(): List<ProviderDescriptor> = buildList {
        add(LocalNoOpAudioProvider().descriptor)
        if (modelStore.hasFinalModel()) {
            add(LocalWhisperProvider(modelStore.finalModelPath(), modelStore.appContext).descriptor)
        }
        val cloudSettings = CloudProviderSettings(modelStore.appContext)
        val secretStore = SecretStore(modelStore.appContext)
        cloudSettings.selectedModel(CloudModelProvider.OPENAI, PipelineStage.TRANSCRIPTION)
            ?.takeIf { secretStore.hasSecret(SecretStore.OPENAI_API_KEY) }
            ?.let { model ->
            add(
                OpenAiWhisperProvider(
                    secretStore,
                    model
                ).descriptor
            )
        }
        cloudSettings.selectedModel(CloudModelProvider.GEMINI, PipelineStage.TRANSCRIPTION)
            ?.takeIf { secretStore.hasSecret(SecretStore.GEMINI_API_KEY) }
            ?.let { add(GeminiFlashLiteTranscriptionProvider(secretStore, it).descriptor) }
        if (modelStore.hasSpeakerModel()) {
            add(LocalDiarizationProvider(modelStore.speakerModelPath(), modelStore.appContext).descriptor)
        }
        add(LocalBasicTextFormatterProvider().descriptor)
        cloudSettings.selectedModel(CloudModelProvider.OPENAI, PipelineStage.TEXT_FORMATTING)
            ?.takeIf { secretStore.hasSecret(SecretStore.OPENAI_API_KEY) }
            ?.let { add(OpenAiTextFormattingProvider(secretStore, it).descriptor) }
        cloudSettings.selectedModel(CloudModelProvider.GEMINI, PipelineStage.TEXT_FORMATTING)
            ?.takeIf { secretStore.hasSecret(SecretStore.GEMINI_API_KEY) }
            ?.let { add(GeminiFlashLiteTextFormattingProvider(secretStore, it).descriptor) }
        if (modelStore.hasQwenSummaryModel()) {
            add(LocalQwenSummaryProvider(modelStore.qwenSummaryModelPath(), modelStore.appContext).descriptor)
        }
        cloudSettings.selectedModel(CloudModelProvider.OPENAI, PipelineStage.SUMMARIZATION)
            ?.takeIf { secretStore.hasSecret(SecretStore.OPENAI_API_KEY) }
            ?.let { model ->
            add(
                OpenAiSummaryProvider(
                    secretStore,
                    model
                ).descriptor
            )
        }
        cloudSettings.selectedModel(CloudModelProvider.GEMINI, PipelineStage.SUMMARIZATION)
            ?.takeIf { secretStore.hasSecret(SecretStore.GEMINI_API_KEY) }
            ?.let { add(GeminiFlashLiteSummaryProvider(secretStore, it).descriptor) }
    }

    private fun requireCloudModel(provider: CloudModelProvider, stage: PipelineStage): String =
        checkNotNull(CloudProviderSettings(modelStore.appContext).selectedModel(provider, stage)) {
            AppLanguage.text("${provider.displayName}の${stage.displayName()}モデルが未選択です", "No ${stage.displayName()} model selected for ${provider.displayName}")
        }

    private fun PipelineStage.displayName(): String = when (this) {
        PipelineStage.TRANSCRIPTION -> AppLanguage.text("音声認識", "Transcription")
        PipelineStage.TEXT_FORMATTING -> AppLanguage.text("テキスト整形", "Text formatting")
        PipelineStage.SUMMARIZATION -> AppLanguage.text("要約", "Summary")
        PipelineStage.AUDIO_PREPROCESSING -> AppLanguage.text("音声前処理", "Audio preprocessing")
        PipelineStage.DIARIZATION -> AppLanguage.text("話者分離", "Speaker separation")
    }
}
