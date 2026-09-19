package com.gijiroku.benchmark

import android.content.Context

data class AiExecutionSecurityScope(
    val consentKey: String,
    val sendsAudio: Boolean,
    val sendsTranscript: Boolean,
    val providerLabels: List<String>,
    val destinations: List<String>
) {
    val usesCloud: Boolean get() = providerLabels.isNotEmpty()
    val dataLabels: List<String> get() = buildList {
        if (sendsAudio) add(AppLanguage.text("録音音声", "Recorded audio"))
        if (sendsTranscript) add(AppLanguage.text("文字起こし", "Transcript"))
    }
}

/** Builds the exact Provider/model/data scope covered by one 24-hour confirmation. */
object AiExecutionSecurity {
    fun currentScope(context: Context): AiExecutionSecurityScope {
        val providers = ProviderPreferences(context)
        val cloudModels = CloudProviderSettings(context)
        val transcription = providers.selectedProvider(PipelineStage.TRANSCRIPTION)
        val formatting = providers.selectedProvider(PipelineStage.TEXT_FORMATTING)
        val summary = providers.selectedProvider(PipelineStage.SUMMARIZATION)
        val sendsAudio = transcription == ProviderIds.OPENAI_WHISPER ||
            transcription == ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION
        val sendsTranscript = formatting == ProviderIds.OPENAI_RESPONSES_FORMATTING ||
            formatting == ProviderIds.GEMINI_FLASH_LITE_FORMATTING ||
            summary == ProviderIds.OPENAI_RESPONSES_SUMMARY ||
            summary == ProviderIds.GEMINI_FLASH_LITE_SUMMARY
        val labels = buildList {
            if (transcription == ProviderIds.OPENAI_WHISPER) add(AppLanguage.text("OpenAI 音声認識", "OpenAI transcription"))
            if (transcription == ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION) add(AppLanguage.text("Gemini 音声認識", "Gemini transcription"))
            if (formatting == ProviderIds.OPENAI_RESPONSES_FORMATTING) add(AppLanguage.text("OpenAI テキスト整形", "OpenAI text formatting"))
            if (formatting == ProviderIds.GEMINI_FLASH_LITE_FORMATTING) add(AppLanguage.text("Gemini テキスト整形", "Gemini text formatting"))
            if (summary == ProviderIds.OPENAI_RESPONSES_SUMMARY) add(AppLanguage.text("OpenAI 要約", "OpenAI summary"))
            if (summary == ProviderIds.GEMINI_FLASH_LITE_SUMMARY) add(AppLanguage.text("Gemini 要約", "Gemini summary"))
        }
        val destinations = buildList {
            if (transcription == ProviderIds.OPENAI_WHISPER ||
                formatting == ProviderIds.OPENAI_RESPONSES_FORMATTING ||
                summary == ProviderIds.OPENAI_RESPONSES_SUMMARY
            ) {
                add("api.openai.com")
            }
            if (transcription == ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION ||
                formatting == ProviderIds.GEMINI_FLASH_LITE_FORMATTING ||
                summary == ProviderIds.GEMINI_FLASH_LITE_SUMMARY
            ) {
                add("generativelanguage.googleapis.com")
            }
        }
        val selectedModels = buildList {
            if (transcription == ProviderIds.OPENAI_WHISPER) {
                add(cloudModels.selectedModel(CloudModelProvider.OPENAI, PipelineStage.TRANSCRIPTION).orEmpty())
            }
            if (transcription == ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION) {
                add(cloudModels.selectedModel(CloudModelProvider.GEMINI, PipelineStage.TRANSCRIPTION).orEmpty())
            }
            if (formatting == ProviderIds.GEMINI_FLASH_LITE_FORMATTING) {
                add(cloudModels.selectedModel(CloudModelProvider.GEMINI, PipelineStage.TEXT_FORMATTING).orEmpty())
            }
            if (formatting == ProviderIds.OPENAI_RESPONSES_FORMATTING) {
                add(cloudModels.selectedModel(CloudModelProvider.OPENAI, PipelineStage.TEXT_FORMATTING).orEmpty())
            }
            if (summary == ProviderIds.OPENAI_RESPONSES_SUMMARY) {
                add(cloudModels.selectedModel(CloudModelProvider.OPENAI, PipelineStage.SUMMARIZATION).orEmpty())
            }
            if (summary == ProviderIds.GEMINI_FLASH_LITE_SUMMARY) {
                add(cloudModels.selectedModel(CloudModelProvider.GEMINI, PipelineStage.SUMMARIZATION).orEmpty())
            }
        }
        val signature = listOf(
            transcription.orEmpty(), formatting.orEmpty(), summary.orEmpty(),
            sendsAudio.toString(), sendsTranscript.toString(),
            labels.joinToString("|"), destinations.joinToString("|"), selectedModels.joinToString("|")
        ).joinToString("\u001f")
        return AiExecutionSecurityScope(
            consentKey = ArtifactHasher.sha256(signature),
            sendsAudio = sendsAudio,
            sendsTranscript = sendsTranscript,
            providerLabels = labels,
            destinations = destinations.distinct()
        )
    }
}

/**
 * A scoped cloud-data confirmation that lasts for this app process, for at most 24 hours.
 * It is deliberately not written to storage: a restart always requires a new confirmation.
 */
class DailyAiConsentStore(@Suppress("UNUSED_PARAMETER") context: Context) {

    fun isValid(scope: AiExecutionSecurityScope, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (!scope.usesCloud) return true
        val grantedAt = synchronized(grants) { grants[key(scope)] ?: -1L }
        return isGrantValid(grantedAt, nowEpochMs)
    }

    fun grant(scope: AiExecutionSecurityScope, nowEpochMs: Long = System.currentTimeMillis()) {
        require(scope.usesCloud) { "Local processing does not require cloud consent" }
        synchronized(grants) { grants[key(scope)] = nowEpochMs }
    }

    private fun key(scope: AiExecutionSecurityScope) = "grant.$CONSENT_VERSION.${scope.consentKey}"

    companion object {
        const val CONSENT_VERSION = 1
        const val VALIDITY_MS = 24L * 60L * 60L * 1000L
        private val grants = mutableMapOf<String, Long>()

        internal fun isGrantValid(grantedAtEpochMs: Long, nowEpochMs: Long): Boolean {
            val age = nowEpochMs - grantedAtEpochMs
            return grantedAtEpochMs > 0L && age in 0 until VALIDITY_MS
        }
    }
}
