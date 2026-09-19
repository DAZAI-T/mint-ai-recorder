package com.gijiroku.benchmark

import android.content.Context
import java.io.File
import java.util.UUID

data class RecordingReference(
    val meetingId: String,
    val encryptedFilePath: String,
    val createdAtEpochMs: Long,
    val finalized: Boolean,
    val durationMs: Long?
)

data class CryptographicDeletionResult(
    val keysDeleted: Boolean,
    val encryptedFileRemoved: Boolean
)

/** Persists only the reference needed to recover the latest encrypted recording after restart. */
class RecordingStore(context: Context, storageNamespace: String = "") {

    private val appContext = context.applicationContext ?: context
    private val safeNamespace = validateStorageNamespace(storageNamespace)
    private val namespaceSuffix = safeNamespace.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME + namespaceSuffix,
        Context.MODE_PRIVATE
    )
    private val keyManager = MeetingKeyManager(appContext)
    private val recordingsDirectory = File(
        appContext.filesDir,
        RECORDINGS_DIRECTORY + namespaceSuffix
    ).apply { mkdirs() }

    @Synchronized
    fun beginRecording(): RecordingReference {
        val meetingId = UUID.randomUUID().toString()
        val file = File(recordingsDirectory, "$meetingId$ENCRYPTED_EXTENSION")
        val reference = RecordingReference(
            meetingId = meetingId,
            encryptedFilePath = file.absolutePath,
            createdAtEpochMs = System.currentTimeMillis(),
            finalized = false,
            durationMs = null
        )
        keyManager.createMeetingKeys(meetingId)
        try {
            persist(reference)
            EncryptedMeetingDatabase(appContext, safeNamespace).use { it.upsertMeeting(reference) }
            return reference
        } catch (error: Throwable) {
            clearLatestIf(meetingId)
            runCatching {
                EncryptedMeetingDatabase(appContext, safeNamespace).use { it.deleteMeetingRows(meetingId) }
            }
            keyManager.deleteMeetingKeys(meetingId)
            throw error
        }
    }

    @Synchronized
    fun markFinalized(reference: RecordingReference, durationMs: Long): RecordingReference {
        val completed = reference.copy(finalized = true, durationMs = durationMs.coerceAtLeast(0))
        persist(completed)
        EncryptedMeetingDatabase(appContext, safeNamespace).use { it.upsertMeeting(completed) }
        return completed
    }

    @Synchronized
    fun deleteAudio(reference: RecordingReference): CryptographicDeletionResult {
        requirePrivateRecording(reference)
        EncryptedMeetingDatabase(appContext, safeNamespace).use { database ->
            val metadata = database.readMeeting(reference.meetingId)
                ?: EncryptedMeetingMetadata(
                    reference.meetingId,
                    reference.createdAtEpochMs,
                    AudioState.FINALIZED,
                    reference.durationMs
                )
            database.upsertMeeting(metadata.copy(audioState = AudioState.DELETED))
        }
        keyManager.deleteAudioKey(reference.meetingId)
        val removed = removeEncryptedFile(reference)
        clearLatestIf(reference.meetingId)
        return CryptographicDeletionResult(keysDeleted = true, encryptedFileRemoved = removed)
    }

    @Synchronized
    fun deleteMeeting(reference: RecordingReference): CryptographicDeletionResult {
        requirePrivateRecording(reference)
        keyManager.deleteMeetingKeys(reference.meetingId)
        val removed = removeEncryptedFile(reference)
        clearLatestIf(reference.meetingId)
        runCatching {
            EncryptedMeetingDatabase(appContext, safeNamespace).use {
                it.deleteMeetingRows(reference.meetingId)
            }
        }
        return CryptographicDeletionResult(keysDeleted = true, encryptedFileRemoved = removed)
    }

    @Synchronized
    fun latest(): RecordingReference? {
        val meetingId = preferences.getString(KEY_MEETING_ID, null) ?: return null
        if (!meetingId.matches(MEETING_ID_PATTERN)) return null
        val file = File(recordingsDirectory, "$meetingId$ENCRYPTED_EXTENSION")
        if (!file.isFile) return null
        if (!keyManager.hasKey(meetingId, MeetingKeyPurpose.AUDIO)) return null
        val duration = preferences.getLong(KEY_DURATION_MS, DURATION_UNKNOWN).takeIf { it >= 0 }
        return RecordingReference(
            meetingId = meetingId,
            encryptedFilePath = file.absolutePath,
            createdAtEpochMs = preferences.getLong(KEY_CREATED_AT, file.lastModified()),
            finalized = preferences.getBoolean(KEY_FINALIZED, false),
            durationMs = duration
        )
    }

    /** Resolves any retained meeting without exposing its encrypted content. */
    fun find(meetingId: String): RecordingReference? {
        if (!meetingId.matches(MEETING_ID_PATTERN)) return null
        val metadata = EncryptedMeetingDatabase(appContext, safeNamespace).use {
            it.readMeeting(meetingId)
        } ?: return null
        val file = File(recordingsDirectory, "$meetingId$ENCRYPTED_EXTENSION")
        return RecordingReference(
            meetingId = meetingId,
            encryptedFilePath = file.absolutePath,
            createdAtEpochMs = metadata.createdAtEpochMs,
            finalized = metadata.audioState != AudioState.RECORDING,
            durationMs = metadata.durationMs
        )
    }

    /** Allocates only the private destination path; callers must create keys before writing. */
    fun importedAudioFile(meetingId: String): File {
        require(meetingId.matches(MEETING_ID_PATTERN)) { "Invalid meeting ID" }
        return File(recordingsDirectory, "$meetingId$ENCRYPTED_EXTENSION")
    }

    @Synchronized
    fun registerRestored(reference: RecordingReference) {
        requirePrivateRecording(reference)
        check(keyManager.hasKey(reference.meetingId, MeetingKeyPurpose.CONTENT)) {
            "Restored content key is unavailable"
        }
        if (File(reference.encryptedFilePath).isFile) {
            check(keyManager.hasKey(reference.meetingId, MeetingKeyPurpose.AUDIO)) {
                "Restored audio key is unavailable"
            }
        }
        persist(reference)
    }

    private fun persist(reference: RecordingReference) {
        check(isInsideRecordingsDirectory(File(reference.encryptedFilePath))) {
            "Recording path is outside private storage"
        }
        val stored = preferences.edit()
            .putString(KEY_MEETING_ID, reference.meetingId)
            .putLong(KEY_CREATED_AT, reference.createdAtEpochMs)
            .putBoolean(KEY_FINALIZED, reference.finalized)
            .putLong(KEY_DURATION_MS, reference.durationMs ?: DURATION_UNKNOWN)
            .commit()
        check(stored) { "Could not persist recording reference" }
    }

    private fun clearLatestIf(meetingId: String) {
        if (preferences.getString(KEY_MEETING_ID, null) != meetingId) return
        preferences.edit().clear().commit()
    }

    private fun isInsideRecordingsDirectory(file: File): Boolean {
        val root = recordingsDirectory.canonicalFile
        val candidate = file.canonicalFile
        return candidate.parentFile == root && candidate.name.endsWith(ENCRYPTED_EXTENSION)
    }

    private fun requirePrivateRecording(reference: RecordingReference) {
        require(reference.meetingId.matches(MEETING_ID_PATTERN)) { "Invalid meeting ID" }
        require(isInsideRecordingsDirectory(File(reference.encryptedFilePath))) {
            "Recording path is outside private storage"
        }
    }

    private fun removeEncryptedFile(reference: RecordingReference): Boolean {
        val file = File(reference.encryptedFilePath)
        return !file.exists() || file.delete()
    }

    companion object {
        private const val PREFERENCES_NAME = "gijiroku_recording_store_v1"
        private const val RECORDINGS_DIRECTORY = "recordings"
        private const val ENCRYPTED_EXTENSION = ".gjraud"
        private const val KEY_MEETING_ID = "latest.meeting_id"
        private const val KEY_CREATED_AT = "latest.created_at"
        private const val KEY_FINALIZED = "latest.finalized"
        private const val KEY_DURATION_MS = "latest.duration_ms"
        private const val DURATION_UNKNOWN = -1L
        private val MEETING_ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        private fun validateStorageNamespace(value: String): String {
            require(value.matches(Regex("[a-zA-Z0-9_-]*"))) { "Invalid storage namespace" }
            return value
        }
    }
}
