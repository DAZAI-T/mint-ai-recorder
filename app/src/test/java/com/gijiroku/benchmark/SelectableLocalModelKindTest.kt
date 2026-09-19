package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectableLocalModelKindTest {
    @Test
    fun bothRecommendedWhisperModelsAreTranscriptionOptions() {
        assertEquals(
            LocalModelLibraryKind.TRANSCRIPTION,
            selectableLocalModelKind(OfficialModelCatalog.LIVE_WHISPER_ID)
        )
        assertEquals(
            LocalModelLibraryKind.TRANSCRIPTION,
            selectableLocalModelKind(OfficialModelCatalog.FINAL_WHISPER_ID)
        )
    }

    @Test
    fun onlyTheSummaryRecommendationMapsToTheSummaryStage() {
        assertEquals(
            LocalModelLibraryKind.SUMMARIZATION,
            selectableLocalModelKind(OfficialModelCatalog.QWEN_SUMMARY_ID)
        )
        assertNull(selectableLocalModelKind(OfficialModelCatalog.SPEAKER_ID))
    }
}
