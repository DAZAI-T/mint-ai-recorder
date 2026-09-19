package com.gijiroku.benchmark

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** A managed source that yields authenticated, mono PCM without creating a plaintext file. */
interface AudioSource {
    val stableId: String
    val createdAtEpochMs: Long
    val estimatedDurationMs: Long?

    fun load(): LoadedAudio
}

data class LoadedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationMs: Long,
    val sha256: String,
    val finalized: Boolean
)

class EncryptedRecordingAudioSource(
    context: Context,
    private val reference: RecordingReference
) : AudioSource {

    private val keyManager = MeetingKeyManager(context.applicationContext ?: context)
    private val encryptedFile = File(reference.encryptedFilePath)

    override val stableId: String = reference.meetingId
    override val createdAtEpochMs: Long = reference.createdAtEpochMs
    override val estimatedDurationMs: Long? = reference.durationMs

    override fun load(): LoadedAudio {
        require(encryptedFile.isFile) { AppLanguage.text("暗号化録音が見つかりません", "Encrypted recording not found") }
        return keyManager.useKey(reference.meetingId, MeetingKeyPurpose.AUDIO) { key ->
            val digest = MessageDigest.getInstance("SHA-256")
            val samples = PcmFloatAccumulator()
            try {
                val inspection = EncryptedAudioReader.read(
                    file = encryptedFile,
                    key = key,
                    allowIncomplete = !reference.finalized
                ) { pcm ->
                    digest.update(pcm)
                    samples.appendPcm16LittleEndian(pcm)
                }
                require(inspection.metadata.channels == 1) { AppLanguage.text("モノラル音声のみ処理できます", "Only mono audio can be processed") }
                require(inspection.metadata.bitsPerSample == 16) { AppLanguage.text("PCM16音声のみ処理できます", "Only PCM16 audio can be processed") }
                LoadedAudio(
                    samples = samples.take(),
                    sampleRate = inspection.metadata.sampleRate,
                    durationMs = inspection.plaintextBytes * 1000L /
                        (inspection.metadata.sampleRate * 2L),
                    sha256 = digest.digest().toHex(),
                    finalized = inspection.finalized
                )
            } catch (error: Throwable) {
                samples.clear()
                throw error
            }
        }
    }
}

private class PcmFloatAccumulator {
    private var values = FloatArray(INITIAL_CAPACITY)
    private var size = 0

    fun appendPcm16LittleEndian(bytes: ByteArray) {
        if (bytes.size % 2 != 0) throw EncryptedAudioFormatException("PCM16 data is not sample-aligned")
        val required = size.toLong() + bytes.size / 2L
        if (required > Int.MAX_VALUE) throw IllegalStateException(AppLanguage.text("録音が長すぎて処理できません", "Recording is too long to process"))
        ensureCapacity(required.toInt())
        var byteIndex = 0
        while (byteIndex < bytes.size) {
            val low = bytes[byteIndex++].toInt() and 0xff
            val high = bytes[byteIndex++].toInt()
            values[size++] = ((high shl 8) or low).toShort() / 32768f
        }
    }

    fun take(): FloatArray {
        val result = values.copyOf(size)
        clear()
        return result
    }

    fun clear() {
        values.fill(0f)
        values = FloatArray(0)
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= values.size) return
        var next = values.size.coerceAtLeast(1)
        while (next < required) {
            val grown = (next.toLong() * 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (grown == next) throw IllegalStateException(AppLanguage.text("録音が長すぎて処理できません", "Recording is too long to process"))
            next = grown
        }
        val replacement = values.copyOf(next)
        values.fill(0f)
        values = replacement
    }

    companion object {
        private const val INITIAL_CAPACITY = 16_000 * 10
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
