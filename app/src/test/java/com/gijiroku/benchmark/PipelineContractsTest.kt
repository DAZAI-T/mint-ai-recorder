package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PipelineContractsTest {

    @Test
    fun artifactHasherProducesStableSha256ForTextAndFile() {
        val expected = "ba7816bf8f01cfea414140de5dae2223" +
            "b00361a396177a9cb410ff61f20015ad"
        val file = File.createTempFile("artifact-hash", ".txt")
        try {
            file.writeText("abc", Charsets.UTF_8)
            assertEquals(expected, ArtifactHasher.sha256("abc"))
            assertEquals(expected, ArtifactHasher.sha256(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun localProviderDescriptorDoesNotDeclareANetworkDestination() {
        val descriptor = LocalWhisperProvider("unused-in-this-test").descriptor

        assertEquals(ProviderIds.LOCAL_WHISPER, descriptor.id)
        assertEquals(PipelineStage.TRANSCRIPTION, descriptor.stage)
        assertEquals(ExecutionMode.LOCAL, descriptor.executionMode)
        assertEquals(setOf(DataKind.AUDIO), descriptor.dataRequired)
        assertNull(descriptor.destination)
    }
}
