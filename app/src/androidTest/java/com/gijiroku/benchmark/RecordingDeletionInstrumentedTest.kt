package com.gijiroku.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingDeletionInstrumentedTest {

    @Test
    fun audioOnlyThenFullDeletion_destroyTheIntendedKeysAndData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val namespace = "deletion-${UUID.randomUUID()}"
        val store = RecordingStore(context, namespace)
        val keyManager = MeetingKeyManager(context)
        val reference = store.beginRecording()
        val file = File(reference.encryptedFilePath)
        var databaseFiles = emptyList<File>()

        try {
            keyManager.useKey(reference.meetingId, MeetingKeyPurpose.AUDIO) { key ->
                EncryptedAudioWriter(file, key, durableWrites = true).use {
                    it.appendPcm16(shortArrayOf(1, 2, 3, 4))
                }
            }
            val completed = store.markFinalized(reference, durationMs = 1)
            EncryptedMeetingDatabase(context, namespace).use { database ->
                database.appendRevision(
                    reference.meetingId,
                    ArtifactRevisionDraft(
                        artifactId = "transcript",
                        content = "音声削除後も残す本文",
                        revisionKind = RevisionKind.AI_GENERATED,
                        author = "AI"
                    )
                )
            }

            val audioDeletion = store.deleteAudio(completed)
            assertTrue(audioDeletion.keysDeleted)
            assertTrue(audioDeletion.encryptedFileRemoved)
            assertFalse(file.exists())
            assertFalse(keyManager.hasKey(reference.meetingId, MeetingKeyPurpose.AUDIO))
            assertTrue(keyManager.hasKey(reference.meetingId, MeetingKeyPurpose.CONTENT))
            assertNull(store.latest())
            EncryptedMeetingDatabase(context, namespace).use { database ->
                assertEquals(AudioState.DELETED, database.readMeeting(reference.meetingId)?.audioState)
                assertEquals(
                    "音声削除後も残す本文",
                    database.currentRevision(reference.meetingId, "transcript")?.content
                )
                databaseFiles = database.filesForSecurityInspection()
            }

            val fullDeletion = store.deleteMeeting(completed)
            assertTrue(fullDeletion.keysDeleted)
            assertFalse(keyManager.hasKey(reference.meetingId, MeetingKeyPurpose.CONTENT))
            assertFalse(keyManager.hasKey(reference.meetingId, MeetingKeyPurpose.AUDIO))
            EncryptedMeetingDatabase(context, namespace).use { database ->
                assertNull(database.readMeeting(reference.meetingId))
                databaseFiles = database.filesForSecurityInspection()
            }
        } finally {
            runCatching { keyManager.deleteMeetingKeys(reference.meetingId) }
            file.delete()
            databaseFiles.forEach { it.delete() }
        }
    }
}
