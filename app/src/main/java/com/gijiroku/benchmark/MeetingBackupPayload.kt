package com.gijiroku.benchmark

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

data class BackupAudioManifest(
    val metadata: EncryptedAudioMetadata,
    val plaintextBytes: Long,
    val finalized: Boolean
) {
    init {
        require(plaintextBytes >= 0 && plaintextBytes % 2L == 0L) { "Invalid PCM16 byte length" }
    }
}

data class MeetingBackupManifest(
    val snapshot: EncryptedMeetingSnapshot,
    val audio: BackupAudioManifest?
)

/** Strict, schema-based codec. It has no fields for API secrets, device keys or voice embeddings. */
object MeetingBackupManifestCodec {
    fun encode(value: MeetingBackupManifest): ByteArray {
        val metadata = value.snapshot.metadata
        val root = JSONObject()
            .put("formatVersion", PAYLOAD_FORMAT_VERSION)
            .put(
                "meeting",
                JSONObject()
                    .put("meetingId", metadata.meetingId)
                    .put("createdAtEpochMs", metadata.createdAtEpochMs)
                    .put("audioState", metadata.audioState.name)
                    .put("durationMs", metadata.durationMs ?: JSONObject.NULL)
            )
            .put("revisions", JSONArray(value.snapshot.revisions.map(::revisionToBackupJson)))
            .put("currentRevisionIds", JSONObject(value.snapshot.currentRevisionIds))
            .put(
                "workingDrafts",
                JSONArray(value.snapshot.workingDrafts.map { draft ->
                    JSONObject()
                        .put("artifactId", draft.artifactId)
                        .put("content", draft.content)
                        .put("updatedAtEpochMs", draft.updatedAtEpochMs)
                })
            )
            .put(
                "providerRuns",
                JSONArray(value.snapshot.providerRuns.map(::providerRunToBackupJson))
            )
            .put(
                "audio",
                value.audio?.let { audio ->
                    JSONObject()
                        .put("sampleRate", audio.metadata.sampleRate)
                        .put("channels", audio.metadata.channels)
                        .put("bitsPerSample", audio.metadata.bitsPerSample)
                        .put("plaintextBytes", audio.plaintextBytes)
                        .put("finalized", audio.finalized)
                } ?: JSONObject.NULL
            )
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): MeetingBackupManifest {
        if (bytes.size !in 2..MAX_MANIFEST_BYTES) throw PortableBackupException("Backup manifest is too large")
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (error: Throwable) {
            throw PortableBackupException("Backup manifest is damaged", error)
        }
        if (root.optInt("formatVersion", -1) != PAYLOAD_FORMAT_VERSION) {
            throw PortableBackupException("Unsupported backup payload version")
        }
        val meetingJson = requiredObject(root, "meeting")
        val metadata = try {
            EncryptedMeetingMetadata(
                meetingId = meetingJson.getString("meetingId"),
                createdAtEpochMs = meetingJson.getLong("createdAtEpochMs"),
                audioState = AudioState.valueOf(meetingJson.getString("audioState")),
                durationMs = nullableLong(meetingJson, "durationMs")
            )
        } catch (error: Throwable) {
            throw PortableBackupException("Backup meeting metadata is invalid", error)
        }
        val revisionArray = requiredArray(root, "revisions")
        if (revisionArray.length() > MAX_REVISIONS) throw PortableBackupException("Too many backup revisions")
        val revisions = buildList {
            for (index in 0 until revisionArray.length()) {
                add(revisionFromBackupJson(requiredArrayObject(revisionArray, index)))
            }
        }
        val currentJson = requiredObject(root, "currentRevisionIds")
        val current = buildMap {
            val keys = currentJson.keys()
            while (keys.hasNext()) {
                val artifactId = keys.next()
                put(artifactId, currentJson.getString(artifactId))
            }
        }
        val draftArray = requiredArray(root, "workingDrafts")
        if (draftArray.length() > MAX_DRAFTS) throw PortableBackupException("Too many backup drafts")
        val drafts = buildList {
            for (index in 0 until draftArray.length()) {
                val draft = requiredArrayObject(draftArray, index)
                add(
                    SnapshotWorkingDraft(
                        artifactId = draft.getString("artifactId"),
                        content = draft.getString("content"),
                        updatedAtEpochMs = draft.getLong("updatedAtEpochMs")
                    )
                )
            }
        }
        val runArray = requiredArray(root, "providerRuns")
        if (runArray.length() > MAX_PROVIDER_RUNS) throw PortableBackupException("Too many provider runs")
        val runs = buildList {
            for (index in 0 until runArray.length()) {
                add(providerRunFromBackupJson(requiredArrayObject(runArray, index)))
            }
        }
        val audio = if (root.isNull("audio")) {
            null
        } else {
            val audioJson = requiredObject(root, "audio")
            val plaintextBytes = audioJson.getLong("plaintextBytes")
            if (plaintextBytes < 0 || plaintextBytes > MAX_AUDIO_BYTES) {
                throw PortableBackupException("Backup audio size is invalid")
            }
            try {
                BackupAudioManifest(
                    metadata = EncryptedAudioMetadata(
                        sampleRate = audioJson.getInt("sampleRate"),
                        channels = audioJson.getInt("channels"),
                        bitsPerSample = audioJson.getInt("bitsPerSample")
                    ),
                    plaintextBytes = plaintextBytes,
                    finalized = audioJson.getBoolean("finalized")
                )
            } catch (error: Throwable) {
                throw PortableBackupException("Backup audio metadata is invalid", error)
            }
        }
        return MeetingBackupManifest(
            snapshot = EncryptedMeetingSnapshot(metadata, revisions, current, drafts, runs),
            audio = audio
        )
    }

    private fun revisionToBackupJson(record: ArtifactRevisionRecord): JSONObject = JSONObject()
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
            JSONArray(record.sourceRanges.map { range ->
                JSONObject().put("startMs", range.startMs).put("endMs", range.endMs)
            })
        )
        .put("cacheKey", record.cacheKey ?: JSONObject.NULL)
        .put("content", record.content)

    private fun revisionFromBackupJson(json: JSONObject): ArtifactRevisionRecord = try {
        ArtifactRevisionRecord(
            revisionId = json.getString("revisionId"),
            meetingId = json.getString("meetingId"),
            artifactId = json.getString("artifactId"),
            parentRevisionId = nullableString(json, "parentRevisionId"),
            revisionKind = RevisionKind.valueOf(json.getString("revisionKind")),
            createdAtEpochMs = json.getLong("createdAtEpochMs"),
            contentHash = json.getString("contentHash"),
            inputRevisionIds = jsonStringList(json.getJSONArray("inputRevisionIds")),
            providerRunId = nullableString(json, "providerRunId"),
            providerId = nullableString(json, "providerId"),
            modelId = nullableString(json, "modelId"),
            promptVersion = nullableString(json, "promptVersion"),
            author = json.getString("author"),
            sourceRanges = jsonObjectList(json.getJSONArray("sourceRanges")).map { range ->
                ArtifactSourceRange(range.getLong("startMs"), range.getLong("endMs"))
            },
            cacheKey = nullableString(json, "cacheKey"),
            content = json.getString("content")
        )
    } catch (error: Throwable) {
        throw PortableBackupException("Backup revision is invalid", error)
    }

    private fun providerRunToBackupJson(entry: ExecutionHistoryEntry): JSONObject = JSONObject()
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

    private fun providerRunFromBackupJson(json: JSONObject): ExecutionHistoryEntry = try {
        ExecutionHistoryEntry(
            runId = json.getString("runId"),
            stage = PipelineStage.valueOf(json.getString("stage")),
            providerId = json.getString("providerId"),
            status = ExecutionStatus.valueOf(json.getString("status")),
            startedAtEpochMs = json.getLong("startedAtEpochMs"),
            durationMs = json.getLong("durationMs"),
            inputHash = json.getString("inputHash"),
            estimatedCostUsd = nullableDouble(json, "estimatedCostUsd"),
            destination = nullableString(json, "destination"),
            errorType = nullableString(json, "errorType"),
            presetId = nullableString(json, "presetId"),
            modelId = nullableString(json, "modelId")
        )
    } catch (error: Throwable) {
        throw PortableBackupException("Backup provider history is invalid", error)
    }
}

object MeetingBackupPayloadWriter {
    fun write(
        output: OutputStream,
        manifest: MeetingBackupManifest,
        writeAudio: ((OutputStream) -> Unit)?
    ) {
        val manifestBytes = MeetingBackupManifestCodec.encode(manifest)
        if (manifestBytes.size > MAX_MANIFEST_BYTES) {
            manifestBytes.fill(0)
            throw PortableBackupException("Backup manifest is too large")
        }
        val audioBytes = manifest.audio?.plaintextBytes ?: 0L
        if ((manifest.audio == null) != (writeAudio == null)) {
            manifestBytes.fill(0)
            throw PortableBackupException("Backup audio source does not match manifest")
        }
        try {
            val target = DataOutputStream(output)
            target.write(PAYLOAD_MAGIC)
            target.writeInt(PAYLOAD_FORMAT_VERSION)
            target.writeInt(manifestBytes.size)
            target.write(manifestBytes)
            target.writeLong(audioBytes)
            if (writeAudio != null) {
                val limited = ExactLengthOutputStream(output, audioBytes)
                writeAudio(limited)
                limited.requireComplete()
            }
        } finally {
            manifestBytes.fill(0)
        }
    }
}

fun interface BackupAudioSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)
}

/** Incrementally parses decrypted payload chunks without buffering long recordings in RAM. */
class MeetingBackupPayloadConsumer(
    private val onManifest: (MeetingBackupManifest) -> BackupAudioSink?
) {
    private enum class State { PREFIX, MANIFEST, AUDIO_LENGTH, AUDIO, DONE }

    private var state = State.PREFIX
    private val prefix = ByteArray(PAYLOAD_PREFIX_BYTES)
    private var prefixSize = 0
    private var manifestBytes: ByteArray? = null
    private var manifestSize = 0
    private val audioLengthBytes = ByteArray(Long.SIZE_BYTES)
    private var audioLengthSize = 0
    private var audioRemaining = 0L
    private var audioSink: BackupAudioSink? = null
    var manifest: MeetingBackupManifest? = null
        private set

    fun accept(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            when (state) {
                State.PREFIX -> {
                    val count = minOf(bytes.size - offset, prefix.size - prefixSize)
                    bytes.copyInto(prefix, prefixSize, offset, offset + count)
                    prefixSize += count
                    offset += count
                    if (prefixSize == prefix.size) parsePrefix()
                }

                State.MANIFEST -> {
                    val target = requireNotNull(manifestBytes)
                    val count = minOf(bytes.size - offset, target.size - manifestSize)
                    bytes.copyInto(target, manifestSize, offset, offset + count)
                    manifestSize += count
                    offset += count
                    if (manifestSize == target.size) {
                        manifest = try {
                            MeetingBackupManifestCodec.decode(target)
                        } finally {
                            target.fill(0)
                            manifestBytes = null
                        }
                        state = State.AUDIO_LENGTH
                    }
                }

                State.AUDIO_LENGTH -> {
                    val count = minOf(bytes.size - offset, audioLengthBytes.size - audioLengthSize)
                    bytes.copyInto(audioLengthBytes, audioLengthSize, offset, offset + count)
                    audioLengthSize += count
                    offset += count
                    if (audioLengthSize == audioLengthBytes.size) parseAudioLength()
                }

                State.AUDIO -> {
                    val count = minOf(bytes.size - offset.toLong(), audioRemaining).toInt()
                    audioSink?.write(bytes, offset, count)
                    offset += count
                    audioRemaining -= count
                    if (audioRemaining == 0L) state = State.DONE
                }

                State.DONE -> throw PortableBackupException("Unexpected data after meeting payload")
            }
        }
    }

    fun finish(): MeetingBackupManifest {
        if (state != State.DONE) throw PortableBackupException("Meeting backup payload is incomplete")
        return requireNotNull(manifest)
    }

    private fun parsePrefix() {
        val buffer = ByteBuffer.wrap(prefix)
        val magic = ByteArray(PAYLOAD_MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(PAYLOAD_MAGIC)) throw PortableBackupException("Unknown meeting payload")
        if (buffer.int != PAYLOAD_FORMAT_VERSION) {
            throw PortableBackupException("Unsupported meeting payload version")
        }
        val length = buffer.int
        if (length !in 2..MAX_MANIFEST_BYTES) throw PortableBackupException("Invalid manifest size")
        manifestBytes = ByteArray(length)
        prefix.fill(0)
        state = State.MANIFEST
    }

    private fun parseAudioLength() {
        val length = ByteBuffer.wrap(audioLengthBytes).long
        audioLengthBytes.fill(0)
        val parsedManifest = requireNotNull(manifest)
        val declared = parsedManifest.audio?.plaintextBytes ?: 0L
        if (length != declared || length < 0 || length > MAX_AUDIO_BYTES) {
            throw PortableBackupException("Backup audio length does not match manifest")
        }
        audioRemaining = length
        audioSink = onManifest(parsedManifest)
        if (length > 0 && audioSink == null) {
            // A null sink explicitly validates/discards audio after authentication.
            audioSink = DISCARD_AUDIO_SINK
        }
        state = if (length == 0L) State.DONE else State.AUDIO
    }
}

private class ExactLengthOutputStream(
    private val target: OutputStream,
    private var remaining: Long
) : OutputStream() {
    override fun write(value: Int) {
        if (remaining == 0L) throw PortableBackupException("Backup audio is longer than declared")
        target.write(value)
        remaining--
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid range" }
        if (length.toLong() > remaining) throw PortableBackupException("Backup audio is longer than declared")
        target.write(bytes, offset, length)
        remaining -= length
    }

    fun requireComplete() {
        if (remaining != 0L) throw PortableBackupException("Backup audio is shorter than declared")
    }
}

private fun requiredObject(parent: JSONObject, name: String): JSONObject = try {
    parent.getJSONObject(name)
} catch (error: Throwable) {
    throw PortableBackupException("Backup field $name is invalid", error)
}

private fun requiredArray(parent: JSONObject, name: String): JSONArray = try {
    parent.getJSONArray(name)
} catch (error: Throwable) {
    throw PortableBackupException("Backup field $name is invalid", error)
}

private fun requiredArrayObject(parent: JSONArray, index: Int): JSONObject = try {
    parent.getJSONObject(index)
} catch (error: Throwable) {
    throw PortableBackupException("Backup array entry is invalid", error)
}

private fun nullableString(json: JSONObject, name: String): String? =
    if (json.isNull(name)) null else json.getString(name)

private fun nullableLong(json: JSONObject, name: String): Long? =
    if (json.isNull(name)) null else json.getLong(name)

private fun nullableDouble(json: JSONObject, name: String): Double? =
    if (json.isNull(name)) null else json.getDouble(name)

private fun jsonStringList(array: JSONArray): List<String> = buildList {
    for (index in 0 until array.length()) add(array.getString(index))
}

private fun jsonObjectList(array: JSONArray): List<JSONObject> = buildList {
    for (index in 0 until array.length()) add(requiredArrayObject(array, index))
}

private val DISCARD_AUDIO_SINK = BackupAudioSink { _, _, _ -> }
private val PAYLOAD_MAGIC = "GJRPAY01".toByteArray(Charsets.US_ASCII)
private const val PAYLOAD_FORMAT_VERSION = 1
private const val PAYLOAD_PREFIX_BYTES = 8 + Int.SIZE_BYTES + Int.SIZE_BYTES
private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
private const val MAX_REVISIONS = 100_000
private const val MAX_DRAFTS = 10_000
private const val MAX_PROVIDER_RUNS = 100_000
private const val MAX_AUDIO_BYTES = 1024L * 1024L * 1024L * 1024L
