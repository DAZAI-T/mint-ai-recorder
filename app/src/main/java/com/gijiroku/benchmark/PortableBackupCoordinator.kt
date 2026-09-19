package com.gijiroku.benchmark

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

data class BackupPreview(
    val sourceMeetingId: String,
    val createdAtEpochMs: Long,
    val revisionCount: Int,
    val providerRunCount: Int,
    val audioPlaintextBytes: Long,
    val hasAudio: Boolean,
    val requiredFreeBytes: Long,
    internal val backupSha256: ByteArray,
    internal val manifestSha256: ByteArray
)

data class BackupRestoreResult(
    val meetingId: String,
    val duplicatedBecauseOfCollision: Boolean,
    val audioRestored: Boolean
)

/** Coordinates the portable format with encrypted meeting/audio storage. All methods are blocking. */
class PortableBackupCoordinator(
    context: Context,
    private val storageNamespace: String = ""
) {
    private val appContext = context.applicationContext ?: context
    private val keyManager = MeetingKeyManager(appContext)
    private val recordingStore = RecordingStore(appContext, storageNamespace)

    fun create(
        reference: RecordingReference,
        output: OutputStream,
        passphrase: CharArray,
        enableRecoveryKey: Boolean
    ): PortableBackupWriteResult {
        try {
            val snapshot = EncryptedMeetingDatabase(appContext, storageNamespace).use { database ->
                database.exportMeetingSnapshot(reference.meetingId)
            }
            MeetingSnapshotValidator.validate(snapshot)
            val encryptedAudioFile = File(reference.encryptedFilePath)
            val audioInspection = when (snapshot.metadata.audioState) {
                AudioState.DELETED -> null
                AudioState.RECORDING, AudioState.FINALIZED -> {
                    if (!encryptedAudioFile.isFile) {
                        throw PortableBackupException("Encrypted recording is missing")
                    }
                    keyManager.useKey(reference.meetingId, MeetingKeyPurpose.AUDIO) { key ->
                        EncryptedAudioReader.read(
                            encryptedAudioFile,
                            key,
                            allowIncomplete = true
                        ) { }
                    }
                }
            }
            val manifest = MeetingBackupManifest(
                snapshot = snapshot,
                audio = audioInspection?.let {
                    BackupAudioManifest(it.metadata, it.plaintextBytes, it.finalized)
                }
            )
            val writer = PortableBackupWriter(
                output = output,
                passphrase = passphrase,
                enableRecoveryKey = enableRecoveryKey
            )
            try {
                MeetingBackupPayloadWriter.write(
                    output = writer,
                    manifest = manifest,
                    writeAudio = audioInspection?.let { expected ->
                        { destination ->
                            val actual = keyManager.useKey(
                                reference.meetingId,
                                MeetingKeyPurpose.AUDIO
                            ) { key ->
                                EncryptedAudioReader.read(
                                    encryptedAudioFile,
                                    key,
                                    allowIncomplete = true,
                                    onPcmChunk = destination::write
                                )
                            }
                            if (actual != expected) {
                                throw PortableBackupException("Recording changed while backup was created")
                            }
                        }
                    }
                )
                return writer.finish()
            } catch (error: Throwable) {
                runCatching { writer.abort() }
                throw error
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /** Authenticates the entire file and parses its schema without changing app state. */
    fun inspect(openInput: () -> InputStream, credential: BackupCredential): BackupPreview {
        val fileDigest = MessageDigest.getInstance("SHA-256")
        var parsedManifest: MeetingBackupManifest? = null
        DigestInputStream(openInput(), fileDigest).use { source ->
            val payload = MeetingBackupPayloadConsumer { manifest ->
                parsedManifest = manifest
                null
            }
            PortableBackupReader.read(source, credential, onPlaintextChunk = payload::accept)
            payload.finish()
        }
        val manifest = requireNotNull(parsedManifest)
        try {
            MeetingSnapshotValidator.validate(manifest.snapshot)
        } catch (error: Throwable) {
            throw PortableBackupException("Backup meeting history is invalid", error)
        }
        val manifestBytes = MeetingBackupManifestCodec.encode(manifest)
        val manifestHash = try {
            MessageDigest.getInstance("SHA-256").digest(manifestBytes)
        } finally {
            manifestBytes.fill(0)
        }
        val audioBytes = manifest.audio?.plaintextBytes ?: 0L
        return BackupPreview(
            sourceMeetingId = manifest.snapshot.metadata.meetingId,
            createdAtEpochMs = manifest.snapshot.metadata.createdAtEpochMs,
            revisionCount = manifest.snapshot.revisions.size,
            providerRunCount = manifest.snapshot.providerRuns.size,
            audioPlaintextBytes = audioBytes,
            hasAudio = manifest.audio != null,
            requiredFreeBytes = requiredFreeBytes(audioBytes),
            backupSha256 = fileDigest.digest(),
            manifestSha256 = manifestHash
        )
    }

    /**
     * Restores a backup already returned by [inspect]. The input is authenticated a second time.
     * Any failure deletes only the newly staged meeting; existing meetings are never modified.
     */
    fun restore(
        openInput: () -> InputStream,
        credential: BackupCredential,
        preview: BackupPreview
    ): BackupRestoreResult {
        if (appContext.filesDir.usableSpace < preview.requiredFreeBytes) {
            clearBackupCredential(credential)
            preview.backupSha256.fill(0)
            preview.manifestSha256.fill(0)
            throw PortableBackupException("Not enough free space to restore backup")
        }
        val database = EncryptedMeetingDatabase(appContext, storageNamespace)
        val sourceId = normalizedUuidOrNull(preview.sourceMeetingId)
        val collision = sourceId == null || database.containsMeetingId(sourceId)
        val targetMeetingId = if (collision) UUID.randomUUID().toString() else requireNotNull(sourceId)
        val audioFile = recordingStore.importedAudioFile(targetMeetingId)
        try {
            keyManager.createMeetingKeys(targetMeetingId)
        } catch (error: Throwable) {
            database.close()
            clearBackupCredential(credential)
            preview.backupSha256.fill(0)
            preview.manifestSha256.fill(0)
            throw error
        }

        var imported = false
        var audioWriter: EncryptedAudioWriter? = null
        var pcmSink: Pcm16RestorationSink? = null
        var restoredManifest: MeetingBackupManifest? = null
        val fileDigest = MessageDigest.getInstance("SHA-256")
        try {
            keyManager.useKey(targetMeetingId, MeetingKeyPurpose.AUDIO) { audioKey ->
                DigestInputStream(openInput(), fileDigest).use { source ->
                    val payload = MeetingBackupPayloadConsumer { manifest ->
                        val encoded = MeetingBackupManifestCodec.encode(manifest)
                        val hash = try {
                            MessageDigest.getInstance("SHA-256").digest(encoded)
                        } finally {
                            encoded.fill(0)
                        }
                        try {
                            if (!MessageDigest.isEqual(hash, preview.manifestSha256)) {
                                throw PortableBackupException("Backup changed after inspection")
                            }
                        } finally {
                            hash.fill(0)
                        }
                        restoredManifest = manifest
                        manifest.audio?.let { audio ->
                            audioWriter = EncryptedAudioWriter(
                                file = audioFile,
                                key = audioKey,
                                metadata = audio.metadata
                            )
                            Pcm16RestorationSink(requireNotNull(audioWriter)).also {
                                pcmSink = it
                            }
                        }
                    }
                    PortableBackupReader.read(source, credential, onPlaintextChunk = payload::accept)
                    payload.finish()
                    pcmSink?.finish()
                    audioWriter?.finish()
                }
            }
            val actualFileHash = fileDigest.digest()
            try {
                if (!MessageDigest.isEqual(actualFileHash, preview.backupSha256)) {
                    throw PortableBackupException("Backup changed after inspection")
                }
            } finally {
                actualFileHash.fill(0)
            }

            val manifest = restoredManifest ?: throw PortableBackupException("Backup has no meeting")
            val restoredAudioState = if (manifest.audio == null) AudioState.DELETED else AudioState.FINALIZED
            database.importMeetingSnapshot(manifest.snapshot, targetMeetingId, restoredAudioState)
            imported = true
            if (manifest.audio == null) {
                keyManager.deleteAudioKey(targetMeetingId)
            } else {
                recordingStore.registerRestored(
                    RecordingReference(
                        meetingId = targetMeetingId,
                        encryptedFilePath = audioFile.absolutePath,
                        createdAtEpochMs = manifest.snapshot.metadata.createdAtEpochMs,
                        finalized = true,
                        durationMs = manifest.snapshot.metadata.durationMs
                    )
                )
            }
            return BackupRestoreResult(targetMeetingId, collision, manifest.audio != null)
        } catch (error: Throwable) {
            runCatching { audioWriter?.abort() }
            if (imported) runCatching { database.deleteMeetingRows(targetMeetingId) }
            runCatching { keyManager.deleteMeetingKeys(targetMeetingId) }
            runCatching { audioFile.delete() }
            throw error
        } finally {
            database.close()
            clearBackupCredential(credential)
            preview.backupSha256.fill(0)
            preview.manifestSha256.fill(0)
        }
    }

    private fun requiredFreeBytes(audioBytes: Long): Long {
        val overhead = 32L * 1024L * 1024L
        return if (audioBytes > Long.MAX_VALUE - overhead) Long.MAX_VALUE else audioBytes + overhead
    }

    private fun normalizedUuidOrNull(value: String): String? = try {
        UUID.fromString(value).toString().takeIf { it == value.lowercase() }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun clearBackupCredential(credential: BackupCredential) {
    if (credential is BackupCredential.Passphrase) credential.characters.fill('\u0000')
}

private class Pcm16RestorationSink(
    private val writer: EncryptedAudioWriter
) : BackupAudioSink {
    private var pendingLowByte: Int? = null

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid range" }
        val samples = ShortArray((length + if (pendingLowByte == null) 0 else 1) / 2 + 1)
        var sampleCount = 0
        var index = offset
        val end = offset + length
        pendingLowByte?.let { low ->
            if (index < end) {
                val high = bytes[index++].toInt()
                samples[sampleCount++] = ((high shl 8) or low).toShort()
                pendingLowByte = null
            }
        }
        while (index + 1 < end) {
            val low = bytes[index++].toInt() and 0xff
            val high = bytes[index++].toInt()
            samples[sampleCount++] = ((high shl 8) or low).toShort()
        }
        if (index < end) pendingLowByte = bytes[index].toInt() and 0xff
        try {
            if (sampleCount > 0) writer.appendPcm16(samples, sampleCount)
        } finally {
            samples.fill(0)
        }
    }

    fun finish() {
        if (pendingLowByte != null) throw PortableBackupException("Backup PCM audio is not sample-aligned")
    }
}
