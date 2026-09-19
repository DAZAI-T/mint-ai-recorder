package com.gijiroku.benchmark

import android.content.Context

/** Stores provider IDs only. Credentials and provider-specific secrets never belong here. */
class ProviderPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("gijiroku_provider_preferences", Context.MODE_PRIVATE)

    init {
        // Old presets already expanded their choices into stage keys. Remove only the shortcut state.
        if (!prefs.getBoolean(KEY_STAGE_SELECTION_MIGRATED, false)) {
            prefs.edit()
                .remove(KEY_PRESET)
                .putBoolean(KEY_STAGE_SELECTION_MIGRATED, true)
                .apply()
        }
    }

    fun selectedProvider(stage: PipelineStage): String? {
        return prefs.getString(key(stage), null) ?: defaultProvider(stage)
    }

    fun selectProvider(stage: PipelineStage, providerId: String?) {
        prefs.edit().apply {
            if (providerId == null) remove(key(stage)) else putString(key(stage), providerId)
        }.apply()
    }

    private fun defaultProvider(stage: PipelineStage): String? = when (stage) {
        PipelineStage.AUDIO_PREPROCESSING -> ProviderIds.LOCAL_NOOP_AUDIO
        PipelineStage.DIARIZATION -> ProviderIds.LOCAL_CAMPPLUS_DIARIZATION
        PipelineStage.TRANSCRIPTION -> ProviderIds.LOCAL_WHISPER
        PipelineStage.TEXT_FORMATTING -> ProviderIds.LOCAL_NOOP_TEXT
        PipelineStage.SUMMARIZATION -> null
    }

    private fun key(stage: PipelineStage) = "selected_provider_${stage.name.lowercase()}"

    companion object {
        private const val KEY_PRESET = "applied_preset"
        private const val KEY_STAGE_SELECTION_MIGRATED = "stage_selection_migrated_v1"
    }
}
