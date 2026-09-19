package com.gijiroku.benchmark

import android.content.Context
import org.json.JSONArray

class CloudProviderSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("gijiroku_cloud_provider_settings", Context.MODE_PRIVATE)

    init {
        migrateFixedModelsOnce()
        ensureOpenAiFormattingModel()
    }

    var openAiTranscriptionModel: String
        get() = selectedModel(CloudModelProvider.OPENAI, PipelineStage.TRANSCRIPTION).orEmpty()
        set(value) {
            addAndSelectModel(CloudModelProvider.OPENAI, PipelineStage.TRANSCRIPTION, value)
        }

    var openAiSummaryModel: String
        get() = selectedModel(CloudModelProvider.OPENAI, PipelineStage.SUMMARIZATION).orEmpty()
        set(value) {
            addAndSelectModel(CloudModelProvider.OPENAI, PipelineStage.SUMMARIZATION, value)
        }

    var openAiFormattingModel: String
        get() = selectedModel(CloudModelProvider.OPENAI, PipelineStage.TEXT_FORMATTING).orEmpty()
        set(value) {
            addAndSelectModel(CloudModelProvider.OPENAI, PipelineStage.TEXT_FORMATTING, value)
        }

    fun models(provider: CloudModelProvider, stage: PipelineStage): List<String> {
        requireSupported(provider, stage)
        val raw = prefs.getString(modelsKey(provider, stage), null) ?: return emptyList()
        return runCatching {
            val values = JSONArray(raw)
            buildList {
                for (index in 0 until values.length()) {
                    values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    fun selectedModel(provider: CloudModelProvider, stage: PipelineStage): String? {
        requireSupported(provider, stage)
        val selected = prefs.getString(selectedKey(provider, stage), null)?.takeIf { it.isNotBlank() }
        return selected?.takeIf { it in models(provider, stage) }
    }

    fun addAndSelectModel(provider: CloudModelProvider, stage: PipelineStage, value: String): String {
        requireSupported(provider, stage)
        val normalized = CloudModelNameValidator.normalize(provider, value)
        val updated = (models(provider, stage) + normalized).distinct()
        prefs.edit()
            .putString(modelsKey(provider, stage), JSONArray(updated).toString())
            .putString(selectedKey(provider, stage), normalized)
            .apply()
        return normalized
    }

    fun selectModel(provider: CloudModelProvider, stage: PipelineStage, value: String) {
        val normalized = CloudModelNameValidator.normalize(provider, value)
        require(normalized in models(provider, stage)) { AppLanguage.text("登録されていないモデルです", "Model is not registered") }
        prefs.edit().putString(selectedKey(provider, stage), normalized).apply()
    }

    fun deleteModel(provider: CloudModelProvider, stage: PipelineStage, value: String) {
        val normalized = CloudModelNameValidator.normalize(provider, value)
        val updated = models(provider, stage).filterNot { it == normalized }
        val editor = prefs.edit().putString(modelsKey(provider, stage), JSONArray(updated).toString())
        if (selectedModel(provider, stage) == normalized) editor.remove(selectedKey(provider, stage))
        editor.apply()
    }

    fun geminiModel(stage: PipelineStage): String? =
        selectedModel(CloudModelProvider.GEMINI, stage)

    private fun migrateFixedModelsOnce() {
        if (prefs.getBoolean(KEY_MODEL_CATALOG_MIGRATED, false)) return
        val oldOpenAiTranscription = prefs.getString(
            KEY_OPENAI_TRANSCRIPTION_MODEL,
            DEFAULT_OPENAI_TRANSCRIPTION_MODEL
        ) ?: DEFAULT_OPENAI_TRANSCRIPTION_MODEL
        val oldOpenAiSummary = prefs.getString(
            KEY_OPENAI_SUMMARY_MODEL,
            DEFAULT_OPENAI_SUMMARY_MODEL
        ) ?: DEFAULT_OPENAI_SUMMARY_MODEL
        addAndSelectModel(CloudModelProvider.OPENAI, PipelineStage.TRANSCRIPTION, oldOpenAiTranscription)
        addAndSelectModel(CloudModelProvider.OPENAI, PipelineStage.SUMMARIZATION, oldOpenAiSummary)
        addAndSelectModel(CloudModelProvider.GEMINI, PipelineStage.TRANSCRIPTION, LEGACY_GEMINI_MODEL)
        addAndSelectModel(CloudModelProvider.GEMINI, PipelineStage.TEXT_FORMATTING, LEGACY_GEMINI_MODEL)
        addAndSelectModel(CloudModelProvider.GEMINI, PipelineStage.SUMMARIZATION, LEGACY_GEMINI_MODEL)
        prefs.edit()
            .remove(KEY_OPENAI_TRANSCRIPTION_MODEL)
            .remove(KEY_OPENAI_SUMMARY_MODEL)
            .putBoolean(KEY_MODEL_CATALOG_MIGRATED, true)
            .apply()
    }

    private fun ensureOpenAiFormattingModel() {
        if (models(CloudModelProvider.OPENAI, PipelineStage.TEXT_FORMATTING).isEmpty()) {
            addAndSelectModel(
                CloudModelProvider.OPENAI,
                PipelineStage.TEXT_FORMATTING,
                DEFAULT_OPENAI_FORMATTING_MODEL
            )
        }
    }

    private fun requireSupported(provider: CloudModelProvider, stage: PipelineStage) {
        require(provider.supportedStages.contains(stage)) { AppLanguage.text("このProviderは対象工程をサポートしていません", "This provider does not support this stage") }
    }

    private fun modelsKey(provider: CloudModelProvider, stage: PipelineStage) =
        "models_${provider.id}_${stage.name.lowercase()}"

    private fun selectedKey(provider: CloudModelProvider, stage: PipelineStage) =
        "selected_${provider.id}_${stage.name.lowercase()}"

    companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_TRANSCRIPTION_MODEL = "whisper-1"
        const val DEFAULT_OPENAI_FORMATTING_MODEL = "gpt-5-mini"
        const val DEFAULT_OPENAI_SUMMARY_MODEL = "gpt-5-mini"
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val LEGACY_GEMINI_MODEL = "gemini-3.5-flash-lite"
        @Deprecated("Choose a model from the catalog for this stage")
        const val GEMINI_FLASH_MODEL = LEGACY_GEMINI_MODEL
        private const val KEY_OPENAI_TRANSCRIPTION_MODEL = "openai_transcription_model"
        private const val KEY_OPENAI_SUMMARY_MODEL = "openai_summary_model"
        private const val KEY_MODEL_CATALOG_MIGRATED = "model_catalog_migrated_v1"
    }
}

enum class CloudModelProvider(
    val id: String,
    val displayName: String,
    val supportedStages: Set<PipelineStage>
) {
    OPENAI(
        "openai",
        "OpenAI",
        setOf(PipelineStage.TRANSCRIPTION, PipelineStage.TEXT_FORMATTING, PipelineStage.SUMMARIZATION)
    ),
    GEMINI(
        "gemini",
        "Google Gemini",
        setOf(PipelineStage.TRANSCRIPTION, PipelineStage.TEXT_FORMATTING, PipelineStage.SUMMARIZATION)
    )
}

object CloudModelNameValidator {
    private val openAiName = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
    private val geminiName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun normalize(provider: CloudModelProvider, value: String): String {
        var normalized = value.trim()
        require(normalized.isNotEmpty()) { AppLanguage.text("モデル名を入力してください", "Enter a model name") }
        require(!normalized.contains("..") && !normalized.contains("?") && !normalized.contains("#")) {
            AppLanguage.text("モデル名に利用できない文字が含まれています", "Model name contains unsupported characters")
        }
        normalized = when (provider) {
            CloudModelProvider.OPENAI -> normalized
            CloudModelProvider.GEMINI -> normalized.removePrefix("models/")
        }
        val accepted = when (provider) {
            CloudModelProvider.OPENAI -> openAiName
            CloudModelProvider.GEMINI -> geminiName
        }
        require(accepted.matches(normalized)) { AppLanguage.text("モデル名の形式が正しくありません", "Invalid model name format") }
        return normalized
    }
}
