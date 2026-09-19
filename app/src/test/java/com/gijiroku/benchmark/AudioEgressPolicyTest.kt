package com.gijiroku.benchmark

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEgressPolicyTest {

    @Test
    fun blocksCloudAudioButNotLocalAudioOrCloudText() {
        val cloudAudio = descriptor(ExecutionMode.CLOUD, setOf(DataKind.AUDIO))
        val localAudio = descriptor(ExecutionMode.LOCAL, setOf(DataKind.AUDIO))
        val cloudText = descriptor(ExecutionMode.CLOUD, setOf(DataKind.TRANSCRIPT))
        AudioEgressPolicy.block()

        expectBlocked { AudioEgressPolicy.requireAllowed(cloudAudio) }
        AudioEgressPolicy.requireAllowed(localAudio)
        AudioEgressPolicy.requireAllowed(cloudText)

        AudioEgressPolicy.allowForCurrentSession()
        AudioEgressPolicy.requireAllowed(cloudAudio)
        AudioEgressPolicy.block()
    }

    @Test
    fun blockingCancelsAnActiveTransmission() {
        var cancelled = false
        AudioEgressPolicy.allowForCurrentSession()

        AudioEgressPolicy.withActiveTransmission(cancel = { cancelled = true }) {
            AudioEgressPolicy.block()
        }

        assertTrue(cancelled)
        expectBlocked {
            AudioEgressPolicy.withActiveTransmission(cancel = {}) { }
        }
    }

    private fun descriptor(mode: ExecutionMode, kinds: Set<DataKind>) = ProviderDescriptor(
        id = "test",
        displayName = "test",
        stage = PipelineStage.TRANSCRIPTION,
        executionMode = mode,
        dataRequired = kinds,
        destination = if (mode == ExecutionMode.CLOUD) "https://example.com" else null
    )

    private fun expectBlocked(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected AudioEgressBlockedException")
        } catch (_: AudioEgressBlockedException) {
            // Expected.
        }
    }
}
