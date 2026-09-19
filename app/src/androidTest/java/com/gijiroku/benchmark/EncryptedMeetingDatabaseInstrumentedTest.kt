package com.gijiroku.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedMeetingDatabaseInstrumentedTest {

    @Test
    fun revisionsDraftsAndRuns_areEncryptedAppendOnlyAndKeyBound() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val namespace = "instrumentation-${UUID.randomUUID()}"
        val meetingId = "db-${UUID.randomUUID()}"
        val keyManager = MeetingKeyManager(context)
        var database: EncryptedMeetingDatabase? = null
        var databaseFiles = emptyList<File>()
        val secrets = listOf(
            "CONFIDENTIAL_TRANSCRIPT_7f96",
            "CONFIDENTIAL_EDIT_c521",
            "CONFIDENTIAL_DRAFT_96af"
        )

        try {
            keyManager.createMeetingKeys(meetingId)
            database = EncryptedMeetingDatabase(context, namespace)
            database.upsertMeeting(
                EncryptedMeetingMetadata(meetingId, 100, AudioState.FINALIZED, 2_500)
            )

            val generated = database.appendRevision(
                meetingId,
                ArtifactRevisionDraft(
                    artifactId = "transcript",
                    content = secrets[0],
                    revisionKind = RevisionKind.AI_GENERATED,
                    providerRunId = "run-1",
                    providerId = ProviderIds.LOCAL_WHISPER,
                    author = "AI",
                    sourceRanges = listOf(ArtifactSourceRange(0, 2_500)),
                    cacheKey = "cache-1"
                )
            )
            val edited = database.appendRevision(
                meetingId,
                ArtifactRevisionDraft(
                    artifactId = "transcript",
                    content = secrets[1],
                    revisionKind = RevisionKind.USER_EDITED,
                    inputRevisionIds = listOf(generated.revisionId),
                    author = "USER"
                )
            )
            val restored = database.restoreRevision(meetingId, generated.revisionId)

            assertEquals(edited.revisionId, restored.parentRevisionId)
            assertEquals(listOf(generated.revisionId), restored.inputRevisionIds)
            assertEquals(RevisionKind.RESTORED, restored.revisionKind)
            assertEquals(secrets[0], restored.content)
            assertEquals(3, database.listRevisions(meetingId, "transcript").size)
            assertEquals(restored.revisionId, database.currentRevision(meetingId, "transcript")?.revisionId)

            database.saveWorkingDraft(meetingId, "transcript", secrets[2])
            assertEquals(secrets[2], database.readWorkingDraft(meetingId, "transcript")?.content)
            database.deleteWorkingDraft(meetingId, "transcript")
            assertNull(database.readWorkingDraft(meetingId, "transcript"))

            val summary = database.appendRevision(
                meetingId,
                ArtifactRevisionDraft(
                    artifactId = "summary",
                    content = "encrypted-summary",
                    revisionKind = RevisionKind.AI_GENERATED,
                    author = "AI"
                )
            )
            assertEquals(summary.revisionId, database.currentRevision(meetingId, "summary")?.revisionId)
            database.clearCurrentRevision(meetingId, "summary")
            assertNull(database.currentRevision(meetingId, "summary"))
            assertEquals(1, database.listRevisions(meetingId, "summary").size)

            val history = ExecutionHistoryEntry(
                runId = "run-1",
                stage = PipelineStage.TRANSCRIPTION,
                providerId = ProviderIds.LOCAL_WHISPER,
                status = ExecutionStatus.SUCCEEDED,
                startedAtEpochMs = 200,
                durationMs = 300,
                inputHash = "input-hash",
                estimatedCostUsd = null,
                destination = null,
                errorType = null
            )
            database.appendProviderRun(meetingId, history)
            assertEquals(listOf(history), database.listProviderRuns(meetingId))
            assertEquals(AudioState.FINALIZED, database.readMeeting(meetingId)?.audioState)

            databaseFiles = database.filesForSecurityInspection()
            database.close()
            database = null

            val reopened = EncryptedMeetingDatabase(context, namespace)
            assertEquals(secrets[0], reopened.currentRevision(meetingId, "transcript")?.content)
            databaseFiles = reopened.filesForSecurityInspection()
            reopened.close()

            databaseFiles.filter(File::isFile).forEach { file ->
                val bytes = file.readBytes()
                try {
                    secrets.forEach { secret ->
                        assertFalse("Plaintext leaked into ${file.name}", bytes.contains(secret.toByteArray(StandardCharsets.UTF_8)))
                    }
                } finally {
                    bytes.fill(0)
                }
            }

            keyManager.deleteMeetingKeys(meetingId)
            val locked = EncryptedMeetingDatabase(context, namespace)
            try {
                try {
                    locked.currentRevision(meetingId, "transcript")
                    fail("Deleted content key must make revisions unreadable")
                } catch (_: IllegalStateException) {
                    // Expected cryptographic deletion.
                }
                assertNull(RecordingStore(context, "unrelated-test-store").latest())
            } finally {
                locked.close()
            }
        } finally {
            database?.close()
            keyManager.deleteMeetingKeys(meetingId)
            databaseFiles.forEach { it.delete() }
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > size) return false
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
}
