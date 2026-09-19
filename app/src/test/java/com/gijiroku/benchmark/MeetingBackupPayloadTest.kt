package com.gijiroku.benchmark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class MeetingBackupPayloadTest {

    @Test
    fun manifestAndAudioRoundTripAcrossArbitraryChunkBoundaries() {
        val manifest = sampleManifest()
        val audio = ByteArray(4_102) { (it * 13).toByte() }
        val payloadBytes = ByteArrayOutputStream().also { output ->
            MeetingBackupPayloadWriter.write(output, manifest) { destination ->
                destination.write(audio)
            }
        }.toByteArray()
        val restoredAudio = ByteArrayOutputStream()
        val consumer = MeetingBackupPayloadConsumer {
            BackupAudioSink { bytes, offset, length -> restoredAudio.write(bytes, offset, length) }
        }

        payloadBytes.forEach { byte -> consumer.accept(byteArrayOf(byte)) }
        val restored = consumer.finish()

        assertEquals(manifest.snapshot.metadata, restored.snapshot.metadata)
        assertEquals(manifest.snapshot.revisions, restored.snapshot.revisions)
        assertEquals(manifest.snapshot.currentRevisionIds, restored.snapshot.currentRevisionIds)
        assertEquals(manifest.snapshot.workingDrafts, restored.snapshot.workingDrafts)
        assertEquals(manifest.snapshot.providerRuns, restored.snapshot.providerRuns)
        assertEquals(manifest.audio, restored.audio)
        assertArrayEquals(audio, restoredAudio.toByteArray())
    }

    @Test
    fun schemaDoesNotSerializeCredentialsOrVoiceEmbeddings() {
        val encoded = MeetingBackupManifestCodec.encode(sampleManifest())
        val text = String(encoded, Charsets.UTF_8)

        assertFalse(text.contains("apiKey", ignoreCase = true))
        assertFalse(text.contains("oauth", ignoreCase = true))
        assertFalse(text.contains("embedding", ignoreCase = true))
        assertFalse(text.contains("keystore", ignoreCase = true))
    }

    @Test
    fun declaredAudioLengthMustMatchWrittenBytes() {
        val output = ByteArrayOutputStream()
        assertThrows(PortableBackupException::class.java) {
            MeetingBackupPayloadWriter.write(output, sampleManifest()) { destination ->
                destination.write(byteArrayOf(1, 2, 3))
            }
        }
    }

    private fun sampleManifest(): MeetingBackupManifest {
        val meetingId = "12345678-1234-4234-8234-123456789abc"
        val revision = ArtifactRevisionRecord(
            revisionId = "rev-1",
            meetingId = meetingId,
            artifactId = "transcript",
            parentRevisionId = null,
            revisionKind = RevisionKind.AI_GENERATED,
            createdAtEpochMs = 110,
            contentHash = ArtifactHasher.sha256("meeting text"),
            inputRevisionIds = emptyList(),
            providerRunId = "run-1",
            providerId = ProviderIds.LOCAL_WHISPER,
            modelId = "small",
            promptVersion = null,
            author = "AI",
            sourceRanges = listOf(ArtifactSourceRange(0, 100)),
            cacheKey = "cache",
            content = "meeting text"
        )
        return MeetingBackupManifest(
            snapshot = EncryptedMeetingSnapshot(
                metadata = EncryptedMeetingMetadata(meetingId, 100, AudioState.FINALIZED, 128),
                revisions = listOf(revision),
                currentRevisionIds = mapOf("transcript" to revision.revisionId),
                workingDrafts = listOf(SnapshotWorkingDraft("summary", "draft", 120)),
                providerRuns = listOf(
                    ExecutionHistoryEntry(
                        runId = "run-1",
                        stage = PipelineStage.TRANSCRIPTION,
                        providerId = ProviderIds.LOCAL_WHISPER,
                        status = ExecutionStatus.SUCCEEDED,
                        startedAtEpochMs = 100,
                        durationMs = 10,
                        inputHash = "hash",
                        estimatedCostUsd = null,
                        destination = null,
                        errorType = null,
                        modelId = "gemini-3.5-flash"
                    )
                )
            ),
            audio = BackupAudioManifest(EncryptedAudioMetadata(), 4_102, true)
        )
    }
}
