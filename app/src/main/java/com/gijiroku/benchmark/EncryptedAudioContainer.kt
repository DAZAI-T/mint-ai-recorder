package com.gijiroku.benchmark

import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedAudioMetadata(
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16
) {
    init {
        require(sampleRate in 8_000..384_000) { "Unsupported sample rate" }
        require(channels in 1..8) { "Unsupported channel count" }
        require(bitsPerSample == 16) { "Only PCM16 is currently supported" }
    }
}

data class EncryptedAudioInspection(
    val metadata: EncryptedAudioMetadata,
    val finalized: Boolean,
    val chunkCount: Long,
    val plaintextBytes: Long
)

class EncryptedAudioFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Append-only authenticated container for PCM16 recording data.
 *
 * The fixed header, every PCM chunk and the completion trailer have independent AES-GCM tags.
 * A file without a valid trailer is never considered complete, but [EncryptedAudioReader] can
 * recover the authenticated prefix after a recorder/process crash.
 */
class EncryptedAudioWriter(
    file: File,
    private val key: SecretKey,
    val metadata: EncryptedAudioMetadata = EncryptedAudioMetadata(),
    private val chunkPlaintextBytes: Int = DEFAULT_CHUNK_PLAINTEXT_BYTES,
    private val durableWrites: Boolean = true,
    private val secureRandom: SecureRandom = SecureRandom()
) : Closeable {

    private val output: RandomAccessFile
    private val headerPrefix: ByteArray
    private val pending: ByteArray
    private var pendingSize = 0
    private var chunkIndex = 0L
    private var totalPlaintextBytes = 0L
    private var closed = false

    init {
        require(key.algorithm.equals("AES", ignoreCase = true)) { "AES key required" }
        require(chunkPlaintextBytes in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) {
            "Chunk size out of range"
        }
        require(chunkPlaintextBytes % PCM16_BYTES == 0) { "Chunk size must align to PCM16" }

        file.parentFile?.mkdirs()
        output = RandomAccessFile(file, "rw").apply { setLength(0) }
        pending = ByteArray(chunkPlaintextBytes)
        headerPrefix = buildHeaderPrefix(metadata, chunkPlaintextBytes, randomBytes(FILE_ID_BYTES))
        writeAuthenticatedHeader()
    }

    @Synchronized
    fun appendPcm16(samples: ShortArray, length: Int = samples.size) {
        check(!closed) { "Writer is closed" }
        require(length in 0..samples.size) { "Invalid sample length" }

        var sampleIndex = 0
        while (sampleIndex < length) {
            val capacityInSamples = (pending.size - pendingSize) / PCM16_BYTES
            val toCopy = minOf(capacityInSamples, length - sampleIndex)
            var destination = pendingSize
            repeat(toCopy) {
                val value = samples[sampleIndex++].toInt()
                pending[destination++] = (value and 0xff).toByte()
                pending[destination++] = ((value ushr 8) and 0xff).toByte()
            }
            pendingSize = destination
            if (pendingSize == pending.size) flushChunk()
        }
    }

    @Synchronized
    fun finish() {
        if (closed) return
        try {
            flushChunk()
            writeTrailer()
            syncIfRequired()
        } finally {
            pending.fill(0)
            closed = true
            output.close()
        }
    }

    override fun close() = finish()

    /** Closes an incomplete destination without writing a completion trailer. */
    @Synchronized
    fun abort() {
        if (closed) return
        pending.fill(0)
        closed = true
        output.close()
    }

    private fun writeAuthenticatedHeader() {
        val nonce = randomBytes(NONCE_BYTES)
        val tag = encrypt(ByteArray(0), nonce, headerPrefix)
        try {
            output.write(headerPrefix)
            output.write(nonce)
            output.write(tag)
            syncIfRequired()
        } finally {
            nonce.fill(0)
            tag.fill(0)
        }
    }

    private fun flushChunk() {
        if (pendingSize == 0) return
        val nonce = randomBytes(NONCE_BYTES)
        val recordHeader = buildChunkHeader(chunkIndex, pendingSize, nonce)
        val aad = concatenate(headerPrefix, recordHeader.copyOfRange(0, CHUNK_AAD_HEADER_BYTES))
        val plaintext = pending.copyOf(pendingSize)
        val encrypted = encrypt(plaintext, nonce, aad)
        try {
            output.write(recordHeader)
            output.write(encrypted)
            syncIfRequired()
            totalPlaintextBytes += pendingSize
            chunkIndex++
        } finally {
            pending.fill(0, 0, pendingSize)
            pendingSize = 0
            nonce.fill(0)
            recordHeader.fill(0)
            aad.fill(0)
            plaintext.fill(0)
            encrypted.fill(0)
        }
    }

    private fun writeTrailer() {
        val nonce = randomBytes(NONCE_BYTES)
        val trailerHeader = buildTrailerHeader(chunkIndex, totalPlaintextBytes, nonce)
        val aad = concatenate(headerPrefix, trailerHeader.copyOfRange(0, TRAILER_AAD_HEADER_BYTES))
        val tag = encrypt(ByteArray(0), nonce, aad)
        try {
            output.write(trailerHeader)
            output.write(tag)
        } finally {
            nonce.fill(0)
            trailerHeader.fill(0)
            aad.fill(0)
            tag.fill(0)
        }
    }

    private fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(plaintext)
        }

    private fun randomBytes(size: Int) = ByteArray(size).also(secureRandom::nextBytes)

    private fun syncIfRequired() {
        if (durableWrites) output.fd.sync()
    }
}

object EncryptedAudioReader {

    /**
     * Decrypts authenticated chunks in order. The callback must consume/copy [ByteArray]
     * synchronously; it is wiped immediately after the callback returns.
     */
    fun read(
        file: File,
        key: SecretKey,
        allowIncomplete: Boolean = false,
        onMetadata: (EncryptedAudioMetadata) -> Unit = {},
        onPcmChunk: (ByteArray) -> Unit
    ): EncryptedAudioInspection {
        require(key.algorithm.equals("AES", ignoreCase = true)) { "AES key required" }
        RandomAccessFile(file, "r").use { input ->
            val prefix = readExact(input, HEADER_PREFIX_BYTES, allowIncomplete = false)
                ?: throw EncryptedAudioFormatException("Missing audio header")
            val headerNonce = readExact(input, NONCE_BYTES, allowIncomplete = false)
                ?: throw EncryptedAudioFormatException("Missing header nonce")
            val headerTag = readExact(input, GCM_TAG_BYTES, allowIncomplete = false)
                ?: throw EncryptedAudioFormatException("Missing header authentication tag")
            authenticateEmpty(key, headerNonce, prefix, headerTag, "Audio header authentication failed")
            val parsed = parseHeaderPrefix(prefix)
            onMetadata(parsed.metadata)

            var expectedIndex = 0L
            var totalBytes = 0L
            while (true) {
                val markerBytes = readExact(input, Int.SIZE_BYTES, allowIncomplete)
                    ?: return incompleteOrThrow(parsed.metadata, expectedIndex, totalBytes, allowIncomplete)
                val marker = ByteBuffer.wrap(markerBytes).order(ByteOrder.BIG_ENDIAN).int
                when (marker) {
                    CHUNK_MARKER -> {
                        val remainder = readExact(
                            input,
                            CHUNK_RECORD_HEADER_BYTES - Int.SIZE_BYTES,
                            allowIncomplete
                        ) ?: return incompleteOrThrow(
                            parsed.metadata,
                            expectedIndex,
                            totalBytes,
                            allowIncomplete
                        )
                        val recordHeader = concatenate(markerBytes, remainder)
                        val headerBuffer = ByteBuffer.wrap(recordHeader).order(ByteOrder.BIG_ENDIAN)
                        headerBuffer.int
                        val index = headerBuffer.long
                        val plaintextLength = headerBuffer.int
                        val nonce = ByteArray(NONCE_BYTES).also(headerBuffer::get)
                        if (index != expectedIndex) {
                            throw EncryptedAudioFormatException("Unexpected chunk index")
                        }
                        if (plaintextLength !in 1..parsed.chunkPlaintextBytes) {
                            throw EncryptedAudioFormatException("Invalid chunk length")
                        }
                        val encrypted = readExact(
                            input,
                            plaintextLength + GCM_TAG_BYTES,
                            allowIncomplete
                        ) ?: return incompleteOrThrow(
                            parsed.metadata,
                            expectedIndex,
                            totalBytes,
                            allowIncomplete
                        )
                        val aad = concatenate(
                            prefix,
                            recordHeader.copyOfRange(0, CHUNK_AAD_HEADER_BYTES)
                        )
                        var plaintext: ByteArray? = null
                        try {
                            plaintext = decrypt(
                                key,
                                nonce,
                                aad,
                                encrypted,
                                "Audio chunk authentication failed"
                            )
                            onPcmChunk(plaintext)
                        } finally {
                            plaintext?.fill(0)
                            nonce.fill(0)
                            aad.fill(0)
                            encrypted.fill(0)
                            recordHeader.fill(0)
                            remainder.fill(0)
                            markerBytes.fill(0)
                        }
                        expectedIndex++
                        totalBytes += plaintextLength
                    }

                    TRAILER_MARKER -> {
                        val remainder = readExact(
                            input,
                            TRAILER_HEADER_BYTES - Int.SIZE_BYTES,
                            allowIncomplete
                        ) ?: return incompleteOrThrow(
                            parsed.metadata,
                            expectedIndex,
                            totalBytes,
                            allowIncomplete
                        )
                        val trailerHeader = concatenate(markerBytes, remainder)
                        val headerBuffer = ByteBuffer.wrap(trailerHeader).order(ByteOrder.BIG_ENDIAN)
                        headerBuffer.int
                        val chunkCount = headerBuffer.long
                        val declaredBytes = headerBuffer.long
                        val nonce = ByteArray(NONCE_BYTES).also(headerBuffer::get)
                        val tag = readExact(input, GCM_TAG_BYTES, allowIncomplete)
                            ?: return incompleteOrThrow(
                                parsed.metadata,
                                expectedIndex,
                                totalBytes,
                                allowIncomplete
                            )
                        if (chunkCount != expectedIndex || declaredBytes != totalBytes) {
                            throw EncryptedAudioFormatException("Audio trailer counts do not match")
                        }
                        val aad = concatenate(
                            prefix,
                            trailerHeader.copyOfRange(0, TRAILER_AAD_HEADER_BYTES)
                        )
                        authenticateEmpty(
                            key,
                            nonce,
                            aad,
                            tag,
                            "Audio trailer authentication failed"
                        )
                        if (input.filePointer != input.length()) {
                            throw EncryptedAudioFormatException("Trailing data after audio trailer")
                        }
                        return EncryptedAudioInspection(
                            metadata = parsed.metadata,
                            finalized = true,
                            chunkCount = expectedIndex,
                            plaintextBytes = totalBytes
                        )
                    }

                    else -> throw EncryptedAudioFormatException("Unknown audio record marker")
                }
            }
        }
    }

    fun readAllPcm16(
        file: File,
        key: SecretKey,
        allowIncomplete: Boolean = false
    ): Pair<EncryptedAudioInspection, ShortArray> {
        val output = ByteArrayOutputStream()
        val inspection = read(file, key, allowIncomplete) { chunk ->
            output.write(chunk)
        }
        val bytes = output.toByteArray()
        try {
            if (bytes.size % PCM16_BYTES != 0) {
                throw EncryptedAudioFormatException("PCM16 data is not sample-aligned")
            }
            val samples = ShortArray(bytes.size / PCM16_BYTES)
            var byteIndex = 0
            for (sampleIndex in samples.indices) {
                val low = bytes[byteIndex++].toInt() and 0xff
                val high = bytes[byteIndex++].toInt()
                samples[sampleIndex] = ((high shl 8) or low).toShort()
            }
            return inspection to samples
        } finally {
            bytes.fill(0)
        }
    }

    private fun incompleteOrThrow(
        metadata: EncryptedAudioMetadata,
        chunkCount: Long,
        totalBytes: Long,
        allowIncomplete: Boolean
    ): EncryptedAudioInspection {
        if (!allowIncomplete) throw EncryptedAudioFormatException("Audio container is incomplete")
        return EncryptedAudioInspection(metadata, false, chunkCount, totalBytes)
    }
}

private data class ParsedHeader(
    val metadata: EncryptedAudioMetadata,
    val chunkPlaintextBytes: Int
)

private fun buildHeaderPrefix(
    metadata: EncryptedAudioMetadata,
    chunkPlaintextBytes: Int,
    fileId: ByteArray
): ByteArray = ByteBuffer.allocate(HEADER_PREFIX_BYTES).order(ByteOrder.BIG_ENDIAN).run {
    put(MAGIC)
    putInt(FORMAT_VERSION)
    putInt(metadata.sampleRate)
    putShort(metadata.channels.toShort())
    putShort(metadata.bitsPerSample.toShort())
    put(fileId)
    putInt(chunkPlaintextBytes)
    array()
}

private fun parseHeaderPrefix(prefix: ByteArray): ParsedHeader {
    if (prefix.size != HEADER_PREFIX_BYTES) throw EncryptedAudioFormatException("Invalid header size")
    val buffer = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
    val magic = ByteArray(MAGIC.size).also(buffer::get)
    if (!magic.contentEquals(MAGIC)) throw EncryptedAudioFormatException("Unknown audio container")
    if (buffer.int != FORMAT_VERSION) throw EncryptedAudioFormatException("Unsupported audio version")
    val sampleRate = buffer.int
    val channels = buffer.short.toInt()
    val bitsPerSample = buffer.short.toInt()
    buffer.position(buffer.position() + FILE_ID_BYTES)
    val chunkBytes = buffer.int
    val metadata = try {
        EncryptedAudioMetadata(sampleRate, channels, bitsPerSample)
    } catch (error: IllegalArgumentException) {
        throw EncryptedAudioFormatException("Invalid audio metadata", error)
    }
    if (chunkBytes !in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES || chunkBytes % PCM16_BYTES != 0) {
        throw EncryptedAudioFormatException("Invalid audio chunk size")
    }
    return ParsedHeader(metadata, chunkBytes)
}

private fun buildChunkHeader(index: Long, plaintextLength: Int, nonce: ByteArray): ByteArray =
    ByteBuffer.allocate(CHUNK_RECORD_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).run {
        putInt(CHUNK_MARKER)
        putLong(index)
        putInt(plaintextLength)
        put(nonce)
        array()
    }

private fun buildTrailerHeader(chunkCount: Long, totalBytes: Long, nonce: ByteArray): ByteArray =
    ByteBuffer.allocate(TRAILER_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).run {
        putInt(TRAILER_MARKER)
        putLong(chunkCount)
        putLong(totalBytes)
        put(nonce)
        array()
    }

private fun decrypt(
    key: SecretKey,
    nonce: ByteArray,
    aad: ByteArray,
    encrypted: ByteArray,
    failureMessage: String
): ByteArray = try {
    Cipher.getInstance(TRANSFORMATION).run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(aad)
        doFinal(encrypted)
    }
} catch (error: AEADBadTagException) {
    throw EncryptedAudioFormatException(failureMessage, error)
}

private fun authenticateEmpty(
    key: SecretKey,
    nonce: ByteArray,
    aad: ByteArray,
    tag: ByteArray,
    failureMessage: String
) {
    val plaintext = decrypt(key, nonce, aad, tag, failureMessage)
    try {
        if (plaintext.isNotEmpty()) throw EncryptedAudioFormatException(failureMessage)
    } finally {
        plaintext.fill(0)
    }
}

private fun readExact(
    input: RandomAccessFile,
    size: Int,
    allowIncomplete: Boolean
): ByteArray? {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = input.read(result, offset, size - offset)
        if (count < 0) {
            result.fill(0)
            if (allowIncomplete) return null
            throw EOFException("Unexpected end of audio container")
        }
        offset += count
    }
    return result
}

private fun concatenate(first: ByteArray, second: ByteArray): ByteArray =
    ByteArray(first.size + second.size).also {
        first.copyInto(it)
        second.copyInto(it, first.size)
    }

private val MAGIC = "GJRAUD01".toByteArray(Charsets.US_ASCII)
private const val FORMAT_VERSION = 1
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
private const val NONCE_BYTES = 12
private const val FILE_ID_BYTES = 16
private const val PCM16_BYTES = 2
private const val HEADER_PREFIX_BYTES = 40
private const val CHUNK_RECORD_HEADER_BYTES = 28
private const val CHUNK_AAD_HEADER_BYTES = 16
private const val TRAILER_HEADER_BYTES = 32
private const val TRAILER_AAD_HEADER_BYTES = 20
private const val CHUNK_MARKER = 0x43484e4b
private const val TRAILER_MARKER = 0x444f4e45
private const val MIN_CHUNK_BYTES = 2
private const val MAX_CHUNK_BYTES = 1 shl 20
internal const val ENCRYPTED_AUDIO_HEADER_BYTES = HEADER_PREFIX_BYTES + NONCE_BYTES + GCM_TAG_BYTES
internal const val ENCRYPTED_AUDIO_TRAILER_BYTES = TRAILER_HEADER_BYTES + GCM_TAG_BYTES
internal const val DEFAULT_CHUNK_PLAINTEXT_BYTES = 32 * 1024
