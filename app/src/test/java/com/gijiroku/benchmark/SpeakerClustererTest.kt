package com.gijiroku.benchmark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerClustererTest {

    @Test
    fun automaticDetectionSelectsOneToFourSeparatedSpeakerGroups() {
        for (speakerCount in 1..4) {
            val result = SpeakerClusterer.cluster(embeddingsFor(speakerCount))

            assertEquals("speakerCount=$speakerCount", speakerCount, result.speakerCount)
            assertFalse(result.isSpeakerCountSpecified)
        }
    }

    @Test
    fun automaticDetectionKeepsNoisyWindowsFromOneSpeakerTogether() {
        val embeddings = listOf(
            floatArrayOf(1f, 0.12f, 0f),
            floatArrayOf(0.96f, -0.18f, 0.03f),
            floatArrayOf(0.89f, 0.23f, -0.04f),
            floatArrayOf(1.03f, 0.02f, 0.06f)
        )

        val result = SpeakerClusterer.cluster(embeddings)

        assertEquals(1, result.speakerCount)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), result.clusterIds)
    }

    @Test
    fun requestedSpeakerCountOverridesAutomaticSelectionAndIsBoundedByValidWindows() {
        val embeddings = embeddingsFor(2)

        val requested = SpeakerClusterer.cluster(embeddings, numSpeakers = 4)
        val tooLarge = SpeakerClusterer.cluster(embeddings.take(3), numSpeakers = 99)

        assertTrue(requested.isSpeakerCountSpecified)
        assertEquals(4, requested.speakerCount)
        assertEquals(3, tooLarge.speakerCount)
    }

    @Test
    fun missingEmbeddingsAreLeftUnlabelledAndFallBackSafely() {
        val result = SpeakerClusterer.cluster(listOf(null, null))

        assertEquals(0, result.speakerCount)
        assertArrayEquals(intArrayOf(-1, -1), result.clusterIds)
    }

    private fun embeddingsFor(speakerCount: Int): List<FloatArray> {
        return (0 until speakerCount).flatMap { speaker ->
            val base = FloatArray(4) { index -> if (index == speaker) 1f else 0f }
            val variation = FloatArray(4) { index ->
                when {
                    index == speaker -> 0.96f
                    index == (speaker + 1) % 4 -> 0.12f
                    else -> 0f
                }
            }
            listOf(base, variation)
        }
    }
}
