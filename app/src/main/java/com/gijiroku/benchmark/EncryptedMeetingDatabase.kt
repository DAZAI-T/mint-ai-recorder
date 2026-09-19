package com.gijiroku.benchmark

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AudioState { RECORDING, FINALIZED, DELETED }

enum class RevisionKind { AI_GENERATED, USER_EDITED, RESTORED, IMPORTED }

data class ArtifactSourceRange(val startMs: Long, val endMs: Long)

data class ArtifactRevisionDraft(
    val artifactId: String,
    val content: String,
    val revisionKind: RevisionKind,
    val parentRevisionId: String? = null,
    val inputRevisionIds: List<String> = emptyList(),
    val providerRunId: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val promptVersion: String? = null,
    val author: String,
    val sourceRanges: List<ArtifactSourceRange> = emptyList(),
    val cacheKey: String? = null
)

data class ArtifactRevisionRecord(
    val revisionId: String,
    val meetingId: String,
    val artifactId: String,
    val parentRevisionId: String?,
    val revisionKind: RevisionKind,
    val createdAtEpochMs: Long,
    val contentHash: String,
    val inputRevisionIds: List<String>,
    val providerRunId: String?,
    val providerId: String?,
    val modelId: String?,
    val promptVersion: String?,
    val author: String,
    val sourceRanges: List<ArtifactSourceRange>,
    val cacheKey: String?,
    val content: String
)

data class EncryptedMeetingMetadata(
    val meetingId: String,
    val createdAtEpochMs: Long,
    val audioState: AudioState,
    val durationMs: Long?
)

data class WorkingDraft(val content: String, val updatedAtEpochMs: Long)

data class SnapshotWorkingDraft(
    val artifactId: String,
    val content: String,
    val updatedAtEpochMs: Long
)

/** Plaintext exists only in managed memory and inside the authenticated portable-backup stream. */
data class EncryptedMeetingSnapshot(
    val metadata: EncryptedMeetingMetadata,
    val revisions: List<ArtifactRevisionRecord>,
    val currentRevisionIds: Map<String, String>,
    val workingDrafts: List<SnapshotWorkingDraft>,
    val providerRuns: List<ExecutionHistoryEntry>
)

object MeetingSnapshotValidator {
    private val artifactIdPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")

    fun validate(snapshot: EncryptedMeetingSnapshot) {
        val sourceMeetingId = snapshot.metadata.meetingId
        check(snapshot.revisions.all { it.meetingId == sourceMeetingId }) {
            "Snapshot contains revisions from another meeting"
        }
        snapshot.revisions.forEach { checkArtifactId(it.artifactId) }
        snapshot.workingDrafts.forEach { checkArtifactId(it.artifactId) }
        snapshot.currentRevisionIds.keys.forEach(::checkArtifactId)

        val revisionIds = snapshot.revisions.map { it.revisionId }.toSet()
        check(revisionIds.size == snapshot.revisions.size) { "Duplicate revision IDs in snapshot" }
        snapshot.revisions.forEach { revision ->
            check(revision.parentRevisionId == null || revision.parentRevisionId in revisionIds) {
                "Snapshot parent revision is missing"
            }
            check(revision.inputRevisionIds.all { it in revisionIds }) {
                "Snapshot input revision is missing"
            }
            check(revision.contentHash == ArtifactHasher.sha256(revision.content)) {
                "Snapshot revision hash does not match"
            }
        }
        snapshot.currentRevisionIds.forEach { (artifactId, revisionId) ->
            val revision = snapshot.revisions.firstOrNull { it.revisionId == revisionId }
            check(revision?.artifactId == artifactId) { "Snapshot current revision is invalid" }
        }
    }

    private fun checkArtifactId(artifactId: String) {
        check(artifactId.matches(artifactIdPattern)) { "Invalid artifact ID in snapshot" }
    }
}

/**
 * SQLite index whose confidential values are individually protected with the meeting CONTENT key.
 * Only random identifiers and ordering timestamps remain in SQLite columns.
 */
class EncryptedMeetingDatabase(
    context: Context,
    databaseNamespace: String = ""
) : SQLiteOpenHelper(
    context.applicationContext ?: context,
    databaseName(databaseNamespace),
    null,
    DATABASE_VERSION
) {

    private val appContext = context.applicationContext ?: context
    private val keyManager = MeetingKeyManager(appContext)
    private val secureRandom = SecureRandom()
    private val databaseFile = appContext.getDatabasePath(databaseName(databaseNamespace))

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) != 0) { "Could not enable secure_delete" }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE meetings (
                meeting_id TEXT PRIMARY KEY NOT NULL,
                sort_created_at INTEGER NOT NULL,
                payload_nonce BLOB NOT NULL,
                payload_ciphertext BLOB NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE artifact_revisions (
                revision_id TEXT PRIMARY KEY NOT NULL,
                meeting_id TEXT NOT NULL,
                artifact_id TEXT NOT NULL,
                sort_created_at INTEGER NOT NULL,
                payload_nonce BLOB NOT NULL,
                payload_ciphertext BLOB NOT NULL,
                FOREIGN KEY(meeting_id) REFERENCES meetings(meeting_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX artifact_revision_order ON artifact_revisions(meeting_id, artifact_id, sort_created_at)"
        )
        db.execSQL(
            """
            CREATE TABLE current_artifacts (
                meeting_id TEXT NOT NULL,
                artifact_id TEXT NOT NULL,
                revision_id TEXT NOT NULL,
                PRIMARY KEY(meeting_id, artifact_id),
                FOREIGN KEY(meeting_id) REFERENCES meetings(meeting_id) ON DELETE CASCADE,
                FOREIGN KEY(revision_id) REFERENCES artifact_revisions(revision_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE working_drafts (
                meeting_id TEXT NOT NULL,
                artifact_id TEXT NOT NULL,
                sort_updated_at INTEGER NOT NULL,
                payload_nonce BLOB NOT NULL,
                payload_ciphertext BLOB NOT NULL,
                PRIMARY KEY(meeting_id, artifact_id),
                FOREIGN KEY(meeting_id) REFERENCES meetings(meeting_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE provider_runs (
                entry_id TEXT PRIMARY KEY NOT NULL,
                meeting_id TEXT NOT NULL,
                run_id TEXT NOT NULL,
                sort_started_at INTEGER NOT NULL,
                payload_nonce BLOB NOT NULL,
                payload_ciphertext BLOB NOT NULL,
                FOREIGN KEY(meeting_id) REFERENCES meetings(meeting_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX provider_run_order ON provider_runs(meeting_id, sort_started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported meeting database upgrade from $oldVersion to $newVersion")
    }

    fun upsertMeeting(reference: RecordingReference) {
        val state = if (reference.finalized) AudioState.FINALIZED else AudioState.RECORDING
        upsertMeeting(
            EncryptedMeetingMetadata(
                meetingId = reference.meetingId,
                createdAtEpochMs = reference.createdAtEpochMs,
                audioState = state,
                durationMs = reference.durationMs
            )
        )
    }

    fun upsertMeeting(metadata: EncryptedMeetingMetadata) {
        val json = JSONObject()
            .put("meetingId", metadata.meetingId)
            .put("createdAtEpochMs", metadata.createdAtEpochMs)
            .put("audioState", metadata.audioState.name)
            .put("durationMs", metadata.durationMs ?: JSONObject.NULL)
        val encrypted = encrypt(metadata.meetingId, TABLE_MEETINGS, metadata.meetingId, json.toString())
        try {
            val values = ContentValues().apply {
                put("meeting_id", metadata.meetingId)
                put("sort_created_at", metadata.createdAtEpochMs)
                put("payload_nonce", encrypted.nonce)
                put("payload_ciphertext", encrypted.ciphertext)
            }
            writableDatabase.beginTransaction()
            try {
                val inserted = writableDatabase.insertWithOnConflict(
                    TABLE_MEETINGS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (inserted == -1L) {
                    writableDatabase.update(
                        TABLE_MEETINGS,
                        values,
                        "meeting_id=?",
                        arrayOf(metadata.meetingId)
                    )
                }
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        } finally {
            encrypted.clear()
        }
    }

    fun readMeeting(meetingId: String): EncryptedMeetingMetadata? {
        readableDatabase.query(
            TABLE_MEETINGS,
            arrayOf("payload_nonce", "payload_ciphertext"),
            "meeting_id=?",
            arrayOf(meetingId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val json = JSONObject(decryptRow(cursor, meetingId, TABLE_MEETINGS, meetingId))
            return EncryptedMeetingMetadata(
                meetingId = json.getString("meetingId"),
                createdAtEpochMs = json.getLong("createdAtEpochMs"),
                audioState = AudioState.valueOf(json.getString("audioState")),
                durationMs = json.longOrNull("durationMs")
            )
        }
    }

    fun containsMeetingId(meetingId: String): Boolean = meetingExists(readableDatabase, meetingId)

    fun listMeetingIds(): List<String> = readableDatabase.query(
        TABLE_MEETINGS,
        arrayOf("meeting_id"),
        null,
        null,
        null,
        null,
        "sort_created_at ASC, rowid ASC"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    fun deleteMeetingRows(meetingId: String) {
        writableDatabase.delete(TABLE_MEETINGS, "meeting_id=?", arrayOf(meetingId))
    }

    fun appendRevision(
        meetingId: String,
        draft: ArtifactRevisionDraft,
        makeCurrent: Boolean = true
    ): ArtifactRevisionRecord {
        validateArtifactId(draft.artifactId)
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(meetingExists(db, meetingId)) { "Meeting does not exist" }
            val currentId = currentRevisionId(db, meetingId, draft.artifactId)
            val parentId = draft.parentRevisionId ?: currentId
            if (parentId != null) {
                val parent = readRevision(db, meetingId, parentId) ?: error("Parent revision does not exist")
                check(parent.artifactId == draft.artifactId) { "Parent belongs to a different artifact" }
            }
            draft.inputRevisionIds.forEach { inputId ->
                check(readRevision(db, meetingId, inputId) != null) { "Input revision does not exist" }
            }

            val record = ArtifactRevisionRecord(
                revisionId = UUID.randomUUID().toString(),
                meetingId = meetingId,
                artifactId = draft.artifactId,
                parentRevisionId = parentId,
                revisionKind = draft.revisionKind,
                createdAtEpochMs = System.currentTimeMillis(),
                contentHash = ArtifactHasher.sha256(draft.content),
                inputRevisionIds = draft.inputRevisionIds,
                providerRunId = draft.providerRunId,
                providerId = draft.providerId,
                modelId = draft.modelId,
                promptVersion = draft.promptVersion,
                author = draft.author,
                sourceRanges = draft.sourceRanges,
                cacheKey = draft.cacheKey,
                content = draft.content
            )
            val encrypted = encrypt(
                meetingId,
                TABLE_REVISIONS,
                record.revisionId,
                revisionToJson(record).toString()
            )
            try {
                val values = ContentValues().apply {
                    put("revision_id", record.revisionId)
                    put("meeting_id", meetingId)
                    put("artifact_id", record.artifactId)
                    put("sort_created_at", record.createdAtEpochMs)
                    put("payload_nonce", encrypted.nonce)
                    put("payload_ciphertext", encrypted.ciphertext)
                }
                check(db.insertOrThrow(TABLE_REVISIONS, null, values) != -1L)
                if (makeCurrent) setCurrentRevision(db, meetingId, record.artifactId, record.revisionId)
            } finally {
                encrypted.clear()
            }
            db.setTransactionSuccessful()
            return record
        } finally {
            db.endTransaction()
        }
    }

    fun currentRevision(meetingId: String, artifactId: String): ArtifactRevisionRecord? {
        validateArtifactId(artifactId)
        val revisionId = currentRevisionId(readableDatabase, meetingId, artifactId) ?: return null
        return readRevision(readableDatabase, meetingId, revisionId)
    }

    fun listCurrentRevisions(meetingId: String): List<ArtifactRevisionRecord> = readableDatabase.query(
        TABLE_CURRENT,
        arrayOf("revision_id"),
        "meeting_id=?",
        arrayOf(meetingId),
        null,
        null,
        "artifact_id ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val revisionId = cursor.getString(0)
                add(readRevision(readableDatabase, meetingId, revisionId) ?: error("Current revision is missing"))
            }
        }
    }

    fun listRevisions(meetingId: String, artifactId: String): List<ArtifactRevisionRecord> {
        validateArtifactId(artifactId)
        readableDatabase.query(
            TABLE_REVISIONS,
            arrayOf("revision_id", "payload_nonce", "payload_ciphertext"),
            "meeting_id=? AND artifact_id=?",
            arrayOf(meetingId, artifactId),
            null,
            null,
            "sort_created_at ASC, rowid ASC"
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val revisionId = cursor.getString(0)
                    add(revisionFromJson(JSONObject(decryptRow(cursor, meetingId, TABLE_REVISIONS, revisionId, 1))))
                }
            }
        }
    }

    fun findReusableRevision(
        meetingId: String,
        artifactId: String,
        cacheKey: String
    ): ArtifactRevisionRecord? = listRevisions(meetingId, artifactId)
        .lastOrNull { it.cacheKey == cacheKey }

    fun restoreRevision(meetingId: String, revisionId: String): ArtifactRevisionRecord {
        val source = readRevision(readableDatabase, meetingId, revisionId)
            ?: error("Revision does not exist")
        return appendRevision(
            meetingId,
            ArtifactRevisionDraft(
                artifactId = source.artifactId,
                content = source.content,
                revisionKind = RevisionKind.RESTORED,
                inputRevisionIds = listOf(source.revisionId),
                author = "USER",
                sourceRanges = source.sourceRanges
            )
        )
    }

    fun selectCurrentRevision(meetingId: String, revisionId: String) {
        val revision = readRevision(readableDatabase, meetingId, revisionId)
            ?: error("Revision does not exist")
        writableDatabase.beginTransaction()
        try {
            setCurrentRevision(writableDatabase, meetingId, revision.artifactId, revisionId)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun clearCurrentRevision(meetingId: String, artifactId: String) {
        validateArtifactId(artifactId)
        writableDatabase.delete(
            TABLE_CURRENT,
            "meeting_id=? AND artifact_id=?",
            arrayOf(meetingId, artifactId)
        )
    }

    fun saveWorkingDraft(meetingId: String, artifactId: String, content: String) {
        validateArtifactId(artifactId)
        check(meetingExists(writableDatabase, meetingId)) { "Meeting does not exist" }
        val updatedAt = System.currentTimeMillis()
        val rowId = "$meetingId:$artifactId"
        val payload = JSONObject().put("content", content).put("updatedAtEpochMs", updatedAt)
        val encrypted = encrypt(meetingId, TABLE_DRAFTS, rowId, payload.toString())
        try {
            val values = ContentValues().apply {
                put("meeting_id", meetingId)
                put("artifact_id", artifactId)
                put("sort_updated_at", updatedAt)
                put("payload_nonce", encrypted.nonce)
                put("payload_ciphertext", encrypted.ciphertext)
            }
            check(writableDatabase.insertWithOnConflict(
                TABLE_DRAFTS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            ) != -1L) { "Could not save working draft" }
        } finally {
            encrypted.clear()
        }
    }

    fun readWorkingDraft(meetingId: String, artifactId: String): WorkingDraft? {
        validateArtifactId(artifactId)
        readableDatabase.query(
            TABLE_DRAFTS,
            arrayOf("payload_nonce", "payload_ciphertext"),
            "meeting_id=? AND artifact_id=?",
            arrayOf(meetingId, artifactId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val json = JSONObject(decryptRow(cursor, meetingId, TABLE_DRAFTS, "$meetingId:$artifactId"))
            return WorkingDraft(json.getString("content"), json.getLong("updatedAtEpochMs"))
        }
    }

    fun deleteWorkingDraft(meetingId: String, artifactId: String) {
        validateArtifactId(artifactId)
        writableDatabase.delete(
            TABLE_DRAFTS,
            "meeting_id=? AND artifact_id=?",
            arrayOf(meetingId, artifactId)
        )
    }

    fun appendProviderRun(meetingId: String, entry: ExecutionHistoryEntry) {
        check(meetingExists(writableDatabase, meetingId)) { "Meeting does not exist" }
        val entryId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("runId", entry.runId)
            .put("stage", entry.stage.name)
            .put("providerId", entry.providerId)
            .put("status", entry.status.name)
            .put("startedAtEpochMs", entry.startedAtEpochMs)
            .put("durationMs", entry.durationMs)
            .put("inputHash", entry.inputHash)
            .put("estimatedCostUsd", entry.estimatedCostUsd ?: JSONObject.NULL)
            .put("destination", entry.destination ?: JSONObject.NULL)
            .put("errorType", entry.errorType ?: JSONObject.NULL)
            .put("presetId", entry.presetId ?: JSONObject.NULL)
            .put("modelId", entry.modelId ?: JSONObject.NULL)
        val encrypted = encrypt(meetingId, TABLE_RUNS, entryId, payload.toString())
        try {
            val values = ContentValues().apply {
                put("entry_id", entryId)
                put("meeting_id", meetingId)
                put("run_id", entry.runId)
                put("sort_started_at", entry.startedAtEpochMs)
                put("payload_nonce", encrypted.nonce)
                put("payload_ciphertext", encrypted.ciphertext)
            }
            writableDatabase.insertOrThrow(TABLE_RUNS, null, values)
        } finally {
            encrypted.clear()
        }
    }

    fun listProviderRuns(meetingId: String): List<ExecutionHistoryEntry> {
        readableDatabase.query(
            TABLE_RUNS,
            arrayOf("entry_id", "payload_nonce", "payload_ciphertext"),
            "meeting_id=?",
            arrayOf(meetingId),
            null,
            null,
            "sort_started_at ASC, rowid ASC"
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val entryId = cursor.getString(0)
                    val json = JSONObject(decryptRow(cursor, meetingId, TABLE_RUNS, entryId, 1))
                    add(
                        ExecutionHistoryEntry(
                            runId = json.getString("runId"),
                            stage = PipelineStage.valueOf(json.getString("stage")),
                            providerId = json.getString("providerId"),
                            status = ExecutionStatus.valueOf(json.getString("status")),
                            startedAtEpochMs = json.getLong("startedAtEpochMs"),
                            durationMs = json.getLong("durationMs"),
                            inputHash = json.getString("inputHash"),
                            estimatedCostUsd = json.doubleOrNull("estimatedCostUsd"),
                            destination = json.stringOrNull("destination"),
                            errorType = json.stringOrNull("errorType"),
                            presetId = json.stringOrNull("presetId"),
                            modelId = json.stringOrNull("modelId")
                        )
                    )
                }
            }
        }
    }

    fun exportMeetingSnapshot(meetingId: String): EncryptedMeetingSnapshot {
        val metadata = readMeeting(meetingId) ?: error("Meeting does not exist")
        val revisions = readableDatabase.query(
            TABLE_REVISIONS,
            arrayOf("revision_id", "payload_nonce", "payload_ciphertext"),
            "meeting_id=?",
            arrayOf(meetingId),
            null,
            null,
            "sort_created_at ASC, rowid ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val revisionId = cursor.getString(0)
                    add(revisionFromJson(JSONObject(decryptRow(cursor, meetingId, TABLE_REVISIONS, revisionId, 1))))
                }
            }
        }
        val current = readableDatabase.query(
            TABLE_CURRENT,
            arrayOf("artifact_id", "revision_id"),
            "meeting_id=?",
            arrayOf(meetingId),
            null,
            null,
            "artifact_id ASC"
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }
        val drafts = readableDatabase.query(
            TABLE_DRAFTS,
            arrayOf("artifact_id", "payload_nonce", "payload_ciphertext"),
            "meeting_id=?",
            arrayOf(meetingId),
            null,
            null,
            "artifact_id ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val artifactId = cursor.getString(0)
                    val json = JSONObject(
                        decryptRow(cursor, meetingId, TABLE_DRAFTS, "$meetingId:$artifactId", 1)
                    )
                    add(
                        SnapshotWorkingDraft(
                            artifactId,
                            json.getString("content"),
                            json.getLong("updatedAtEpochMs")
                        )
                    )
                }
            }
        }
        return EncryptedMeetingSnapshot(metadata, revisions, current, drafts, listProviderRuns(meetingId))
    }

    /**
     * Imports a fully validated snapshot as one SQLite transaction. Revision IDs are always
     * regenerated, so restoring the same backup twice cannot overwrite an earlier restore.
     */
    fun importMeetingSnapshot(
        snapshot: EncryptedMeetingSnapshot,
        targetMeetingId: String,
        audioState: AudioState = snapshot.metadata.audioState
    ): EncryptedMeetingMetadata {
        check(!meetingExists(readableDatabase, targetMeetingId)) { "Meeting already exists" }
        check(keyManager.hasKey(targetMeetingId, MeetingKeyPurpose.CONTENT)) {
            "Target content key does not exist"
        }
        MeetingSnapshotValidator.validate(snapshot)

        val idMap = snapshot.revisions.associate { it.revisionId to UUID.randomUUID().toString() }
        val importedMetadata = snapshot.metadata.copy(meetingId = targetMeetingId, audioState = audioState)
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertEncryptedMeeting(db, importedMetadata)
            snapshot.revisions.forEach { source ->
                val imported = source.copy(
                    revisionId = idMap.getValue(source.revisionId),
                    meetingId = targetMeetingId,
                    parentRevisionId = source.parentRevisionId?.let(idMap::getValue),
                    inputRevisionIds = source.inputRevisionIds.map(idMap::getValue)
                )
                insertEncryptedRevision(db, imported)
            }
            snapshot.currentRevisionIds.forEach { (artifactId, sourceRevisionId) ->
                setCurrentRevision(db, targetMeetingId, artifactId, idMap.getValue(sourceRevisionId))
            }
            snapshot.workingDrafts.forEach { source ->
                val payload = JSONObject()
                    .put("content", source.content)
                    .put("updatedAtEpochMs", source.updatedAtEpochMs)
                val rowId = "$targetMeetingId:${source.artifactId}"
                val encrypted = encrypt(targetMeetingId, TABLE_DRAFTS, rowId, payload.toString())
                try {
                    db.insertOrThrow(
                        TABLE_DRAFTS,
                        null,
                        ContentValues().apply {
                            put("meeting_id", targetMeetingId)
                            put("artifact_id", source.artifactId)
                            put("sort_updated_at", source.updatedAtEpochMs)
                            put("payload_nonce", encrypted.nonce)
                            put("payload_ciphertext", encrypted.ciphertext)
                        }
                    )
                } finally {
                    encrypted.clear()
                }
            }
            snapshot.providerRuns.forEach { entry -> insertProviderRun(db, targetMeetingId, entry) }
            db.setTransactionSuccessful()
            return importedMetadata
        } finally {
            db.endTransaction()
        }
    }

    fun filesForSecurityInspection(): List<File> = listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal")
    ).filter(File::isFile)

    private fun readRevision(
        db: SQLiteDatabase,
        meetingId: String,
        revisionId: String
    ): ArtifactRevisionRecord? {
        db.query(
            TABLE_REVISIONS,
            arrayOf("payload_nonce", "payload_ciphertext"),
            "meeting_id=? AND revision_id=?",
            arrayOf(meetingId, revisionId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return revisionFromJson(
                JSONObject(decryptRow(cursor, meetingId, TABLE_REVISIONS, revisionId))
            )
        }
    }

    private fun currentRevisionId(db: SQLiteDatabase, meetingId: String, artifactId: String): String? {
        db.query(
            TABLE_CURRENT,
            arrayOf("revision_id"),
            "meeting_id=? AND artifact_id=?",
            arrayOf(meetingId, artifactId),
            null,
            null,
            null
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun setCurrentRevision(
        db: SQLiteDatabase,
        meetingId: String,
        artifactId: String,
        revisionId: String
    ) {
        val values = ContentValues().apply {
            put("meeting_id", meetingId)
            put("artifact_id", artifactId)
            put("revision_id", revisionId)
        }
        db.insertWithOnConflict(TABLE_CURRENT, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertEncryptedMeeting(db: SQLiteDatabase, metadata: EncryptedMeetingMetadata) {
        val payload = JSONObject()
            .put("meetingId", metadata.meetingId)
            .put("createdAtEpochMs", metadata.createdAtEpochMs)
            .put("audioState", metadata.audioState.name)
            .put("durationMs", metadata.durationMs ?: JSONObject.NULL)
        val encrypted = encrypt(metadata.meetingId, TABLE_MEETINGS, metadata.meetingId, payload.toString())
        try {
            db.insertOrThrow(
                TABLE_MEETINGS,
                null,
                ContentValues().apply {
                    put("meeting_id", metadata.meetingId)
                    put("sort_created_at", metadata.createdAtEpochMs)
                    put("payload_nonce", encrypted.nonce)
                    put("payload_ciphertext", encrypted.ciphertext)
                }
            )
        } finally {
            encrypted.clear()
        }
    }

    private fun insertEncryptedRevision(db: SQLiteDatabase, record: ArtifactRevisionRecord) {
        val encrypted = encrypt(
            record.meetingId,
            TABLE_REVISIONS,
            record.revisionId,
            revisionToJson(record).toString()
        )
        try {
            db.insertOrThrow(
                TABLE_REVISIONS,
                null,
                ContentValues().apply {
                    put("revision_id", record.revisionId)
                    put("meeting_id", record.meetingId)
                    put("artifact_id", record.artifactId)
                    put("sort_created_at", record.createdAtEpochMs)
                    put("payload_nonce", encrypted.nonce)
                    put("payload_ciphertext", encrypted.ciphertext)
                }
            )
        } finally {
            encrypted.clear()
        }
    }

    private fun insertProviderRun(db: SQLiteDatabase, meetingId: String, entry: ExecutionHistoryEntry) {
        val entryId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("runId", entry.runId)
            .put("stage", entry.stage.name)
            .put("providerId", entry.providerId)
            .put("status", entry.status.name)
            .put("startedAtEpochMs", entry.startedAtEpochMs)
            .put("durationMs", entry.durationMs)
            .put("inputHash", entry.inputHash)
            .put("estimatedCostUsd", entry.estimatedCostUsd ?: JSONObject.NULL)
            .put("destination", entry.destination ?: JSONObject.NULL)
            .put("errorType", entry.errorType ?: JSONObject.NULL)
            .put("presetId", entry.presetId ?: JSONObject.NULL)
            .put("modelId", entry.modelId ?: JSONObject.NULL)
        val encrypted = encrypt(meetingId, TABLE_RUNS, entryId, payload.toString())
        try {
            db.insertOrThrow(
                TABLE_RUNS,
                null,
                ContentValues().apply {
                    put("entry_id", entryId)
                    put("meeting_id", meetingId)
                    put("run_id", entry.runId)
                    put("sort_started_at", entry.startedAtEpochMs)
                    put("payload_nonce", encrypted.nonce)
                    put("payload_ciphertext", encrypted.ciphertext)
                }
            )
        } finally {
            encrypted.clear()
        }
    }

    private fun meetingExists(db: SQLiteDatabase, meetingId: String): Boolean {
        db.rawQuery("SELECT 1 FROM meetings WHERE meeting_id=? LIMIT 1", arrayOf(meetingId)).use {
            return it.moveToFirst()
        }
    }

    private fun validateArtifactId(artifactId: String) {
        require(artifactId.matches(ARTIFACT_ID_PATTERN)) { "Invalid artifact ID" }
    }

    private fun encrypt(meetingId: String, table: String, rowId: String, plaintext: String): EncryptedRow {
        val bytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        try {
            val ciphertext = keyManager.useKey(meetingId, MeetingKeyPurpose.CONTENT) { key ->
                cipher(Cipher.ENCRYPT_MODE, key, nonce, aad(table, meetingId, rowId)).doFinal(bytes)
            }
            return EncryptedRow(nonce, ciphertext)
        } finally {
            bytes.fill(0)
        }
    }

    private fun decryptRow(
        cursor: Cursor,
        meetingId: String,
        table: String,
        rowId: String,
        offset: Int = 0
    ): String {
        val nonce = cursor.getBlob(offset)
        val ciphertext = cursor.getBlob(offset + 1)
        var plaintext: ByteArray? = null
        try {
            plaintext = keyManager.useKey(meetingId, MeetingKeyPurpose.CONTENT) { key ->
                cipher(Cipher.DECRYPT_MODE, key, nonce, aad(table, meetingId, rowId)).doFinal(ciphertext)
            }
            return String(plaintext, StandardCharsets.UTF_8)
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun cipher(mode: Int, key: SecretKey, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
            aad.fill(0)
        }

    private fun aad(table: String, meetingId: String, rowId: String): ByteArray =
        "$FORMAT_VERSION|$table|$meetingId|$rowId".toByteArray(StandardCharsets.UTF_8)

    private fun revisionToJson(record: ArtifactRevisionRecord): JSONObject = JSONObject()
        .put("revisionId", record.revisionId)
        .put("meetingId", record.meetingId)
        .put("artifactId", record.artifactId)
        .put("parentRevisionId", record.parentRevisionId ?: JSONObject.NULL)
        .put("revisionKind", record.revisionKind.name)
        .put("createdAtEpochMs", record.createdAtEpochMs)
        .put("contentHash", record.contentHash)
        .put("inputRevisionIds", JSONArray(record.inputRevisionIds))
        .put("providerRunId", record.providerRunId ?: JSONObject.NULL)
        .put("providerId", record.providerId ?: JSONObject.NULL)
        .put("modelId", record.modelId ?: JSONObject.NULL)
        .put("promptVersion", record.promptVersion ?: JSONObject.NULL)
        .put("author", record.author)
        .put(
            "sourceRanges",
            JSONArray(record.sourceRanges.map { JSONObject().put("startMs", it.startMs).put("endMs", it.endMs) })
        )
        .put("cacheKey", record.cacheKey ?: JSONObject.NULL)
        .put("content", record.content)

    private fun revisionFromJson(json: JSONObject): ArtifactRevisionRecord = ArtifactRevisionRecord(
        revisionId = json.getString("revisionId"),
        meetingId = json.getString("meetingId"),
        artifactId = json.getString("artifactId"),
        parentRevisionId = json.stringOrNull("parentRevisionId"),
        revisionKind = RevisionKind.valueOf(json.getString("revisionKind")),
        createdAtEpochMs = json.getLong("createdAtEpochMs"),
        contentHash = json.getString("contentHash"),
        inputRevisionIds = json.getJSONArray("inputRevisionIds").strings(),
        providerRunId = json.stringOrNull("providerRunId"),
        providerId = json.stringOrNull("providerId"),
        modelId = json.stringOrNull("modelId"),
        promptVersion = json.stringOrNull("promptVersion"),
        author = json.getString("author"),
        sourceRanges = json.getJSONArray("sourceRanges").objects().map {
            ArtifactSourceRange(it.getLong("startMs"), it.getLong("endMs"))
        },
        cacheKey = json.stringOrNull("cacheKey"),
        content = json.getString("content")
    )

    private data class EncryptedRow(val nonce: ByteArray, val ciphertext: ByteArray) {
        fun clear() {
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_BASENAME = "gijiroku_meetings_v1"
        private const val FORMAT_VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val TABLE_MEETINGS = "meetings"
        private const val TABLE_REVISIONS = "artifact_revisions"
        private const val TABLE_CURRENT = "current_artifacts"
        private const val TABLE_DRAFTS = "working_drafts"
        private const val TABLE_RUNS = "provider_runs"

        private val ARTIFACT_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")

        private fun databaseName(namespace: String): String {
            require(namespace.matches(Regex("[a-zA-Z0-9_-]*"))) { "Invalid database namespace" }
            return if (namespace.isEmpty()) "$DATABASE_BASENAME.db" else "$DATABASE_BASENAME.$namespace.db"
        }
    }
}

private fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else getString(name)

private fun JSONObject.longOrNull(name: String): Long? =
    if (isNull(name)) null else getLong(name)

private fun JSONObject.doubleOrNull(name: String): Double? =
    if (isNull(name)) null else getDouble(name)

private fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) add(getString(index))
}

private fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) add(getJSONObject(index))
}
