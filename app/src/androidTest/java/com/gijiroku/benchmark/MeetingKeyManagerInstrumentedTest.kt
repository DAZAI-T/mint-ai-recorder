package com.gijiroku.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingKeyManagerInstrumentedTest {

    @Test
    fun latestProductRecording_whenPresentAuthenticatesWithoutPlaintextFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val reference = RecordingStore(context).latest()
        assumeNotNull(reference)

        val audio = EncryptedRecordingAudioSource(context, checkNotNull(reference)).load()
        try {
            assertTrue(audio.finalized)
            assertTrue(audio.samples.isNotEmpty())
            assertTrue(audio.durationMs > 0)
            assertTrue(audio.sha256.matches(Regex("[0-9a-f]{64}")))
        } finally {
            audio.samples.fill(0f)
        }
    }

    @Test
    fun recordingStore_restoresFinalizedAndInterruptedEncryptedAudio() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = RecordingStore(context, storageNamespace = "instrumentation")
        val manager = MeetingKeyManager(context)
        val expected = ShortArray(40_000) { index -> (index * 17 - 20_000).toShort() }
        val references = mutableListOf<RecordingReference>()

        try {
            val finalizedReference = store.beginRecording().also(references::add)
            writeEncryptedRecording(manager, finalizedReference, expected)
            val completed = store.markFinalized(finalizedReference, durationMs = 2_500)

            val restored = RecordingStore(context, storageNamespace = "instrumentation").latest()
            assertEquals(completed, restored)
            val finalizedAudio = EncryptedRecordingAudioSource(context, checkNotNull(restored)).load()
            assertTrue(finalizedAudio.finalized)
            assertEquals(2_500, finalizedAudio.durationMs)
            assertFloatPcmEquals(expected, finalizedAudio.samples)
            finalizedAudio.samples.fill(0f)

            val interruptedReference = store.beginRecording().also(references::add)
            writeEncryptedRecording(manager, interruptedReference, expected)
            val interruptedFile = File(interruptedReference.encryptedFilePath)
            RandomAccessFile(interruptedFile, "rw").use { file ->
                file.setLength(file.length() - AUTHENTICATED_TRAILER_BYTES)
            }

            val interruptedAudio = EncryptedRecordingAudioSource(context, interruptedReference).load()
            assertFalse(interruptedAudio.finalized)
            assertFloatPcmEquals(expected, interruptedAudio.samples)
            interruptedAudio.samples.fill(0f)
        } finally {
            references.forEach { reference ->
                manager.deleteMeetingKeys(reference.meetingId)
                File(reference.encryptedFilePath).delete()
            }
        }
    }

    @Test
    fun keystoreKeys_encryptDecryptAndDeleteIndependently() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = MeetingKeyManager(context)
        val meetingId = "instrumented-${UUID.randomUUID()}"
        val encryptedFile = File(context.cacheDir, "$meetingId.gjraud")
        val expected = ShortArray(40_000) { index -> ((index * 31) % Short.MAX_VALUE).toShort() }

        try {
            manager.createMeetingKeys(meetingId)
            assertTrue(manager.hasKey(meetingId, MeetingKeyPurpose.AUDIO))
            assertTrue(manager.hasKey(meetingId, MeetingKeyPurpose.CONTENT))

            val audioFingerprint = manager.useKey(meetingId, MeetingKeyPurpose.AUDIO) { key ->
                MessageDigest.getInstance("SHA-256").digest(key.encoded)
            }
            val contentFingerprint = manager.useKey(meetingId, MeetingKeyPurpose.CONTENT) { key ->
                MessageDigest.getInstance("SHA-256").digest(key.encoded)
            }
            assertNotEquals(audioFingerprint.contentToString(), contentFingerprint.contentToString())

            manager.useKey(meetingId, MeetingKeyPurpose.AUDIO) { key ->
                EncryptedAudioWriter(
                    file = encryptedFile,
                    key = key,
                    metadata = EncryptedAudioMetadata(sampleRate = 16_000, channels = 1),
                    durableWrites = true
                ).use { writer -> writer.appendPcm16(expected) }
            }

            val (inspection, actual) = manager.useKey(meetingId, MeetingKeyPurpose.AUDIO) { key ->
                EncryptedAudioReader.readAllPcm16(encryptedFile, key)
            }
            assertTrue(inspection.finalized)
            assertArrayEquals(expected, actual)

            try {
                manager.useKey(meetingId, MeetingKeyPurpose.CONTENT) { key ->
                    EncryptedAudioReader.read(encryptedFile, key) { }
                }
                fail("Content key must not decrypt audio data")
            } catch (_: EncryptedAudioFormatException) {
                // Expected: purpose-separated keys cannot decrypt each other's data.
            }

            manager.deleteAudioKey(meetingId)
            assertFalse(manager.hasKey(meetingId, MeetingKeyPurpose.AUDIO))
            assertTrue(manager.hasKey(meetingId, MeetingKeyPurpose.CONTENT))
            try {
                manager.useKey(meetingId, MeetingKeyPurpose.AUDIO) { }
                fail("Deleted audio key must be unavailable")
            } catch (_: IllegalStateException) {
                // Expected after cryptographic deletion.
            }

            manager.deleteMeetingKeys(meetingId)
            assertFalse(manager.hasKey(meetingId, MeetingKeyPurpose.CONTENT))
        } finally {
            manager.deleteMeetingKeys(meetingId)
            encryptedFile.delete()
        }
    }

    @Test
    fun audioKeyFromAnotherMeeting_cannotDecryptRecording() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = MeetingKeyManager(context)
        val meetingA = "meeting-a-${UUID.randomUUID()}"
        val meetingB = "meeting-b-${UUID.randomUUID()}"
        val encryptedFile = File(context.cacheDir, "$meetingA.gjraud")

        try {
            manager.createMeetingKeys(meetingA)
            manager.createMeetingKeys(meetingB)
            manager.useKey(meetingA, MeetingKeyPurpose.AUDIO) { key ->
                EncryptedAudioWriter(
                    file = encryptedFile,
                    key = key,
                    metadata = EncryptedAudioMetadata(sampleRate = 16_000, channels = 1),
                    durableWrites = true
                ).use { it.appendPcm16(shortArrayOf(1, 2, 3, 4)) }
            }

            try {
                manager.useKey(meetingB, MeetingKeyPurpose.AUDIO) { key ->
                    EncryptedAudioReader.readAllPcm16(encryptedFile, key)
                }
                fail("A meeting key must not decrypt another meeting's recording")
            } catch (_: EncryptedAudioFormatException) {
                // Expected: AES-GCM authentication binds the recording to Meeting A's key.
            }
        } finally {
            manager.deleteMeetingKeys(meetingA)
            manager.deleteMeetingKeys(meetingB)
            encryptedFile.delete()
        }
    }

    private fun writeEncryptedRecording(
        manager: MeetingKeyManager,
        reference: RecordingReference,
        samples: ShortArray
    ) {
        manager.useKey(reference.meetingId, MeetingKeyPurpose.AUDIO) { key ->
            EncryptedAudioWriter(
                file = File(reference.encryptedFilePath),
                key = key,
                durableWrites = true
            ).use { it.appendPcm16(samples) }
        }
    }

    private fun assertFloatPcmEquals(expected: ShortArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals(expected[index] / 32768f, actual[index], 0f)
        }
    }

    companion object {
        private const val AUTHENTICATED_TRAILER_BYTES = 4L + 8L + 8L + 12L + 16L
    }
}
