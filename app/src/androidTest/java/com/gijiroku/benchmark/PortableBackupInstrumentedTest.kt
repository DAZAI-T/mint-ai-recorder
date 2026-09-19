package com.gijiroku.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PortableBackupInstrumentedTest {

    @Test
    fun realArgon2IdRoundTripWorksOnDeviceWithoutPlaintextInContainer() {
        val plaintext = "実機Argon2idバックアップ検証用の機密本文".repeat(80).toByteArray()
        val passphraseText = "device-only test passphrase"
        val encrypted = ByteArrayOutputStream()
        val writer = PortableBackupWriter(
            output = encrypted,
            passphrase = passphraseText.toCharArray(),
            kdfParameters = BackupKdfParameters(iterations = 1, memoryKibibytes = 8 * 1024),
            enableRecoveryKey = true,
            chunkSize = 1_024
        )
        writer.write(plaintext)
        val created = writer.finish()

        assertFalse(encrypted.toByteArray().containsSequence(plaintext.copyOfRange(0, 32)))
        assertFalse(encrypted.toByteArray().containsSequence(passphraseText.toByteArray()))

        val restoredWithPassphrase = ByteArrayOutputStream()
        val passphraseInspection = PortableBackupReader.read(
            encrypted.toByteArray().inputStream(),
            BackupCredential.Passphrase(passphraseText.toCharArray()),
            onPlaintextChunk = restoredWithPassphrase::write
        )
        assertArrayEquals(plaintext, restoredWithPassphrase.toByteArray())
        assertFalse(passphraseInspection.usedRecoveryKey)

        val restoredWithRecoveryKey = ByteArrayOutputStream()
        val recoveryInspection = PortableBackupReader.read(
            encrypted.toByteArray().inputStream(),
            BackupCredential.RecoveryKey(created.recoveryKey!!),
            onPlaintextChunk = restoredWithRecoveryKey::write
        )
        assertArrayEquals(plaintext, restoredWithRecoveryKey.toByteArray())
        assertTrue(recoveryInspection.usedRecoveryKey)
    }

    @Test
    fun coordinatorRestoresAudioAndRevisionHistoryAsDuplicateWithoutSecretsInBackup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val namespace = "backup-${UUID.randomUUID()}"
        val store = RecordingStore(context, namespace)
        val keyManager = MeetingKeyManager(context)
        val original = store.beginRecording()
        val originalFile = File(original.encryptedFilePath)
        val samples = ShortArray(2_050) { (it * 7).toShort() }
        val secretText = "BACKUP_SECRET_TRANSCRIPT_d182"
        var restoredMeetingId: String? = null
        var databaseFiles = emptyList<File>()

        try {
            keyManager.useKey(original.meetingId, MeetingKeyPurpose.AUDIO) { key ->
                EncryptedAudioWriter(originalFile, key).use { it.appendPcm16(samples) }
            }
            val completed = store.markFinalized(original, 128)
            EncryptedMeetingDatabase(context, namespace).use { database ->
                val generated = database.appendRevision(
                    original.meetingId,
                    ArtifactRevisionDraft(
                        artifactId = "transcript",
                        content = secretText,
                        revisionKind = RevisionKind.AI_GENERATED,
                        providerId = ProviderIds.LOCAL_WHISPER,
                        author = "AI"
                    )
                )
                database.appendRevision(
                    original.meetingId,
                    ArtifactRevisionDraft(
                        artifactId = "transcript",
                        content = "$secretText-edited",
                        revisionKind = RevisionKind.USER_EDITED,
                        inputRevisionIds = listOf(generated.revisionId),
                        author = "USER"
                    )
                )
                database.saveWorkingDraft(original.meetingId, "summary", "draft-secret")
                database.appendProviderRun(
                    original.meetingId,
                    ExecutionHistoryEntry(
                        runId = "run-local",
                        stage = PipelineStage.TRANSCRIPTION,
                        providerId = ProviderIds.LOCAL_WHISPER,
                        status = ExecutionStatus.SUCCEEDED,
                        startedAtEpochMs = 20,
                        durationMs = 30,
                        inputHash = "hash",
                        estimatedCostUsd = null,
                        destination = null,
                        errorType = null
                    )
                )
            }

            val encryptedBackup = ByteArrayOutputStream()
            val coordinator = PortableBackupCoordinator(context, namespace)
            coordinator.create(
                completed,
                encryptedBackup,
                "integration backup passphrase".toCharArray(),
                enableRecoveryKey = false
            )
            val backupBytes = encryptedBackup.toByteArray()
            assertFalse(backupBytes.containsSequence(secretText.toByteArray()))

            assertThrows(PortableBackupException::class.java) {
                coordinator.inspect(
                    openInput = { backupBytes.inputStream() },
                    credential = BackupCredential.Passphrase("incorrect passphrase".toCharArray())
                )
            }
            EncryptedMeetingDatabase(context, namespace).use { database ->
                assertEquals(listOf(original.meetingId), database.listMeetingIds())
            }

            val previewForChangedFile = coordinator.inspect(
                openInput = { backupBytes.inputStream() },
                credential = BackupCredential.Passphrase("integration backup passphrase".toCharArray())
            )
            val changedBackup = backupBytes.clone().also {
                it[it.size - 60] = (it[it.size - 60].toInt() xor 1).toByte()
            }
            assertThrows(PortableBackupException::class.java) {
                coordinator.restore(
                    openInput = { changedBackup.inputStream() },
                    credential = BackupCredential.Passphrase("integration backup passphrase".toCharArray()),
                    preview = previewForChangedFile
                )
            }
            EncryptedMeetingDatabase(context, namespace).use { database ->
                assertEquals(listOf(original.meetingId), database.listMeetingIds())
            }

            val preview = coordinator.inspect(
                openInput = { backupBytes.inputStream() },
                credential = BackupCredential.Passphrase("integration backup passphrase".toCharArray())
            )
            assertEquals(2, preview.revisionCount)
            assertEquals(1, preview.providerRunCount)
            assertTrue(preview.hasAudio)

            val restored = coordinator.restore(
                openInput = { backupBytes.inputStream() },
                credential = BackupCredential.Passphrase("integration backup passphrase".toCharArray()),
                preview = preview
            )
            restoredMeetingId = restored.meetingId
            assertTrue(restored.duplicatedBecauseOfCollision)
            assertNotEquals(original.meetingId, restored.meetingId)

            EncryptedMeetingDatabase(context, namespace).use { database ->
                assertEquals(
                    "$secretText-edited",
                    database.currentRevision(restored.meetingId, "transcript")?.content
                )
                assertEquals(2, database.listRevisions(restored.meetingId, "transcript").size)
                assertEquals("draft-secret", database.readWorkingDraft(restored.meetingId, "summary")?.content)
                assertEquals(1, database.listProviderRuns(restored.meetingId).size)
                databaseFiles = database.filesForSecurityInspection()
            }
            val restoredFile = store.importedAudioFile(restored.meetingId)
            val restoredSamples = keyManager.useKey(restored.meetingId, MeetingKeyPurpose.AUDIO) { key ->
                EncryptedAudioReader.readAllPcm16(restoredFile, key).second
            }
            assertArrayEquals(samples, restoredSamples)
        } finally {
            runCatching { keyManager.deleteMeetingKeys(original.meetingId) }
            restoredMeetingId?.let { id ->
                runCatching { keyManager.deleteMeetingKeys(id) }
                store.importedAudioFile(id).delete()
            }
            originalFile.delete()
            databaseFiles.forEach(File::delete)
        }
    }
}

private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    for (start in 0..size - needle.size) {
        var matches = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}
