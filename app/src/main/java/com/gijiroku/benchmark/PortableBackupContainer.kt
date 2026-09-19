package com.gijiroku.benchmark

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BackupKdfParameters(
    val iterations: Int = 3,
    val memoryKibibytes: Int = 65_536,
    val parallelism: Int = 2
) {
    init {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid Argon2 iteration count" }
        require(memoryKibibytes in MIN_MEMORY_KIB..MAX_MEMORY_KIB) { "Invalid Argon2 memory cost" }
        require(parallelism in MIN_PARALLELISM..MAX_PARALLELISM) { "Invalid Argon2 parallelism" }
    }

    companion object {
        const val MIN_ITERATIONS = 1
        const val MAX_ITERATIONS = 10
        const val MIN_MEMORY_KIB = 8 * 1024
        const val MAX_MEMORY_KIB = 256 * 1024
        const val MIN_PARALLELISM = 1
        const val MAX_PARALLELISM = 4
    }
}

class PortableBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun interface BackupKeyDeriver {
    /** Returns exactly 32 bytes. The caller wipes the returned array. */
    fun derive(passphrase: CharArray, salt: ByteArray, parameters: BackupKdfParameters): ByteArray
}

/** Android Argon2id v1.3 key derivation. Always invoke off the main thread. */
class Argon2IdBackupKeyDeriver : BackupKeyDeriver {
    override fun derive(
        passphrase: CharArray,
        salt: ByteArray,
        parameters: BackupKdfParameters
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        require(salt.size == SALT_BYTES) { "Invalid salt" }
        val passwordBuffer = encodeUtf8Direct(passphrase)
        val saltBuffer = ByteBuffer.allocateDirect(salt.size).apply {
            put(salt)
            position(0)
        }
        var resultRaw: ByteBuffer? = null
        var resultEncoded: ByteBuffer? = null
        return try {
            val result = Argon2Kt().hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBuffer,
                salt = saltBuffer,
                tCostInIterations = parameters.iterations,
                mCostInKibibyte = parameters.memoryKibibytes,
                parallelism = parameters.parallelism,
                hashLengthInBytes = KEY_BYTES
            )
            resultRaw = result.rawHash
            resultEncoded = result.encodedOutput
            result.rawHashAsByteArray().also {
                check(it.size == KEY_BYTES) { "Unexpected Argon2 output length" }
            }
        } finally {
            wipe(passwordBuffer)
            wipe(saltBuffer)
            resultRaw?.let(::wipe)
            resultEncoded?.let(::wipe)
        }
    }

    private fun encodeUtf8Direct(characters: CharArray): ByteBuffer {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val capacity = (characters.size * encoder.maxBytesPerChar()).toInt().coerceAtLeast(1)
        val destination = ByteBuffer.allocateDirect(capacity)
        val source = CharBuffer.wrap(characters)
        val result = encoder.encode(source, destination, true)
        if (result.isError) result.throwException()
        val flushed = encoder.flush(destination)
        if (flushed.isError) flushed.throwException()
        destination.flip()
        return destination
    }
}

data class PortableBackupWriteResult(
    val recoveryKey: String?,
    val plaintextBytes: Long,
    val chunkCount: Long
)

data class PortableBackupInspection(
    val plaintextBytes: Long,
    val chunkCount: Long,
    val usedRecoveryKey: Boolean,
    val kdfParameters: BackupKdfParameters
)

sealed interface BackupCredential {
    class Passphrase(val characters: CharArray) : BackupCredential
    class RecoveryKey(val encoded: String) : BackupCredential
}

/**
 * Authenticated streaming writer for portable backups.
 *
 * The caller writes an application payload; no plaintext temporary file is created here. A random
 * data key encrypts chunks, while Argon2id and the optional recovery key independently wrap it.
 */
class PortableBackupWriter(
    output: OutputStream,
    passphrase: CharArray,
    private val keyDeriver: BackupKeyDeriver = Argon2IdBackupKeyDeriver(),
    val kdfParameters: BackupKdfParameters = BackupKdfParameters(),
    enableRecoveryKey: Boolean = false,
    private val chunkSize: Int = DEFAULT_BACKUP_CHUNK_BYTES,
    private val secureRandom: SecureRandom = SecureRandom()
) : OutputStream(), Closeable {

    private val target = DataOutputStream(output)
    private val dataKeyBytes = randomBytes(KEY_BYTES)
    private val dataKey: SecretKey = SecretKeySpec(dataKeyBytes, "AES")
    private val pending: ByteArray
    private val headerFingerprint: ByteArray
    private var pendingSize = 0
    private var chunkIndex = 0L
    private var totalPlaintextBytes = 0L
    private var closed = false
    val recoveryKey: String?

    init {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        require(chunkSize in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) { "Invalid backup chunk size" }
        pending = ByteArray(chunkSize)

        val salt = randomBytes(SALT_BYTES)
        val passphraseKey = keyDeriver.derive(passphrase, salt, kdfParameters)
        check(passphraseKey.size == KEY_BYTES) { "Backup key deriver must return 32 bytes" }
        val passphraseNonce = randomBytes(NONCE_BYTES)
        val baseHeader = buildBaseHeader(kdfParameters, salt, enableRecoveryKey)
        val passphraseWrapped = encrypt(
            SecretKeySpec(passphraseKey, "AES"),
            passphraseNonce,
            baseHeader,
            dataKeyBytes
        )

        var recoveryRaw: ByteArray? = null
        var recoveryWrappingKey: ByteArray? = null
        var recoveryNonce: ByteArray? = null
        var recoveryWrapped: ByteArray? = null
        try {
            recoveryKey = if (enableRecoveryKey) {
                recoveryRaw = randomBytes(RECOVERY_KEY_BYTES)
                recoveryWrappingKey = recoveryWrappingKey(recoveryRaw)
                recoveryNonce = randomBytes(NONCE_BYTES)
                val recoveryAad = concatenate(baseHeader, passphraseNonce, passphraseWrapped)
                try {
                    recoveryWrapped = encrypt(
                        SecretKeySpec(recoveryWrappingKey, "AES"),
                        recoveryNonce,
                        recoveryAad,
                        dataKeyBytes
                    )
                } finally {
                    recoveryAad.fill(0)
                }
                RecoveryKeyCodec.encode(recoveryRaw)
            } else {
                null
            }

            val headerWithoutAuthentication = buildHeaderWithoutAuthentication(
                baseHeader,
                passphraseNonce,
                passphraseWrapped,
                recoveryNonce,
                recoveryWrapped
            )
            val headerAuthNonce = randomBytes(NONCE_BYTES)
            val headerAuthTag = encrypt(dataKey, headerAuthNonce, headerWithoutAuthentication, ByteArray(0))
            val completeHeader = concatenate(headerWithoutAuthentication, headerAuthNonce, headerAuthTag)
            try {
                target.writeInt(completeHeader.size)
                target.write(completeHeader)
                headerFingerprint = sha256(completeHeader)
            } finally {
                headerWithoutAuthentication.fill(0)
                headerAuthNonce.fill(0)
                headerAuthTag.fill(0)
                completeHeader.fill(0)
            }
        } catch (error: Throwable) {
            dataKeyBytes.fill(0)
            throw error
        } finally {
            salt.fill(0)
            passphraseKey.fill(0)
            passphraseNonce.fill(0)
            passphraseWrapped.fill(0)
            baseHeader.fill(0)
            recoveryRaw?.fill(0)
            recoveryWrappingKey?.fill(0)
            recoveryNonce?.fill(0)
            recoveryWrapped?.fill(0)
        }
    }

    override fun write(value: Int) {
        val one = byteArrayOf(value.toByte())
        try {
            write(one, 0, 1)
        } finally {
            one.fill(0)
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Backup writer is closed" }
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "Invalid range" }
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, pending.size - pendingSize)
            buffer.copyInto(pending, pendingSize, sourceOffset, sourceOffset + count)
            pendingSize += count
            sourceOffset += count
            remaining -= count
            if (pendingSize == pending.size) flushChunk()
        }
    }

    fun finish(): PortableBackupWriteResult {
        if (closed) throw IllegalStateException("Backup writer is closed")
        try {
            flushChunk()
            writeTrailer()
            target.flush()
            return PortableBackupWriteResult(recoveryKey, totalPlaintextBytes, chunkIndex)
        } finally {
            closed = true
            pending.fill(0)
            dataKeyBytes.fill(0)
            headerFingerprint.fill(0)
            target.close()
        }
    }

    override fun close() {
        if (!closed) finish()
    }

    fun abort() {
        if (closed) return
        closed = true
        pending.fill(0)
        dataKeyBytes.fill(0)
        headerFingerprint.fill(0)
        target.close()
    }

    private fun flushChunk() {
        if (pendingSize == 0) return
        val nonce = randomBytes(NONCE_BYTES)
        val recordHeader = recordHeader(CHUNK_MARKER, chunkIndex, pendingSize, nonce)
        val aad = concatenate(headerFingerprint, recordHeader)
        val plaintext = pending.copyOf(pendingSize)
        val ciphertext = encrypt(dataKey, nonce, aad, plaintext)
        try {
            target.write(recordHeader)
            target.write(ciphertext)
            chunkIndex++
            totalPlaintextBytes += pendingSize
        } finally {
            pending.fill(0, 0, pendingSize)
            pendingSize = 0
            nonce.fill(0)
            recordHeader.fill(0)
            aad.fill(0)
            plaintext.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun writeTrailer() {
        val nonce = randomBytes(NONCE_BYTES)
        val trailer = trailerHeader(chunkIndex, totalPlaintextBytes, nonce)
        val aad = concatenate(headerFingerprint, trailer)
        val tag = encrypt(dataKey, nonce, aad, ByteArray(0))
        try {
            target.write(trailer)
            target.write(tag)
        } finally {
            nonce.fill(0)
            trailer.fill(0)
            aad.fill(0)
            tag.fill(0)
        }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(secureRandom::nextBytes)
}

object PortableBackupReader {
    fun read(
        input: InputStream,
        credential: BackupCredential,
        keyDeriver: BackupKeyDeriver = Argon2IdBackupKeyDeriver(),
        onPlaintextChunk: (ByteArray) -> Unit
    ): PortableBackupInspection {
        val source = DataInputStream(input)
        val headerSize = readInt(source, "Missing backup header")
        if (headerSize !in MIN_HEADER_BYTES..MAX_HEADER_BYTES) {
            throw PortableBackupException("Unsupported or damaged backup")
        }
        val completeHeader = readExact(source, headerSize, "Incomplete backup header")
        var parsed: BackupParsedHeader? = null
        var dataKeyBytes: ByteArray? = null
        var fingerprint: ByteArray? = null
        try {
            val header = parseHeader(completeHeader)
            parsed = header
            dataKeyBytes = unwrapDataKey(header, credential, keyDeriver)
            authenticateHeader(header, dataKeyBytes)
            fingerprint = sha256(completeHeader)
            val dataKey = SecretKeySpec(dataKeyBytes, "AES")

            var expectedIndex = 0L
            var totalBytes = 0L
            while (true) {
                val marker = readInt(source, "Backup is incomplete")
                when (marker) {
                    CHUNK_MARKER -> {
                        val index = readLong(source, "Incomplete backup chunk")
                        val plaintextLength = readInt(source, "Incomplete backup chunk")
                        val nonce = readExact(source, NONCE_BYTES, "Incomplete backup chunk")
                        if (index != expectedIndex || plaintextLength !in 1..MAX_CHUNK_BYTES) {
                            throw PortableBackupException("Backup chunk sequence is invalid")
                        }
                        val ciphertext = readExact(
                            source,
                            plaintextLength + GCM_TAG_BYTES,
                            "Incomplete backup chunk"
                        )
                        val header = recordHeader(marker, index, plaintextLength, nonce)
                        val aad = concatenate(fingerprint, header)
                        var plaintext: ByteArray? = null
                        try {
                            plaintext = decrypt(dataKey, nonce, aad, ciphertext)
                            onPlaintextChunk(plaintext)
                        } finally {
                            plaintext?.fill(0)
                            nonce.fill(0)
                            ciphertext.fill(0)
                            header.fill(0)
                            aad.fill(0)
                        }
                        expectedIndex++
                        totalBytes = safeAdd(totalBytes, plaintextLength.toLong())
                    }

                    TRAILER_MARKER -> {
                        val chunkCount = readLong(source, "Incomplete backup trailer")
                        val plaintextBytes = readLong(source, "Incomplete backup trailer")
                        val nonce = readExact(source, NONCE_BYTES, "Incomplete backup trailer")
                        val tag = readExact(source, GCM_TAG_BYTES, "Incomplete backup trailer")
                        if (chunkCount != expectedIndex || plaintextBytes != totalBytes) {
                            throw PortableBackupException("Backup trailer counts do not match")
                        }
                        val trailer = trailerHeader(chunkCount, plaintextBytes, nonce)
                        val aad = concatenate(fingerprint, trailer)
                        try {
                            val empty = decrypt(dataKey, nonce, aad, tag)
                            if (empty.isNotEmpty()) throw PortableBackupException("Invalid backup trailer")
                            empty.fill(0)
                            if (source.read() != -1) throw PortableBackupException("Trailing data after backup")
                        } finally {
                            nonce.fill(0)
                            tag.fill(0)
                            trailer.fill(0)
                            aad.fill(0)
                        }
                        return PortableBackupInspection(
                            plaintextBytes = totalBytes,
                            chunkCount = chunkCount,
                            usedRecoveryKey = credential is BackupCredential.RecoveryKey,
                            kdfParameters = header.parameters
                        )
                    }

                    else -> throw PortableBackupException("Unknown backup record")
                }
            }
        } finally {
            completeHeader.fill(0)
            dataKeyBytes?.fill(0)
            fingerprint?.fill(0)
            parsed?.clear()
            if (credential is BackupCredential.Passphrase) credential.characters.fill('\u0000')
        }
    }

    private fun unwrapDataKey(
        header: BackupParsedHeader,
        credential: BackupCredential,
        keyDeriver: BackupKeyDeriver
    ): ByteArray = when (credential) {
        is BackupCredential.Passphrase -> {
            val wrappingKey = keyDeriver.derive(credential.characters, header.salt, header.parameters)
            check(wrappingKey.size == KEY_BYTES) { "Backup key deriver must return 32 bytes" }
            try {
                decrypt(
                    SecretKeySpec(wrappingKey, "AES"),
                    header.passphraseNonce,
                    header.baseHeader,
                    header.passphraseWrappedKey
                )
            } finally {
                wrappingKey.fill(0)
            }
        }

        is BackupCredential.RecoveryKey -> {
            val recoveryRaw = RecoveryKeyCodec.decode(credential.encoded)
            val wrappingKey = recoveryWrappingKey(recoveryRaw)
            val nonce = header.recoveryNonce
                ?: throw PortableBackupException("This backup has no recovery key")
            val wrapped = header.recoveryWrappedKey
                ?: throw PortableBackupException("This backup has no recovery key")
            val aad = concatenate(header.baseHeader, header.passphraseNonce, header.passphraseWrappedKey)
            try {
                decrypt(SecretKeySpec(wrappingKey, "AES"), nonce, aad, wrapped)
            } finally {
                recoveryRaw.fill(0)
                wrappingKey.fill(0)
                aad.fill(0)
            }
        }
    }.also {
        if (it.size != KEY_BYTES) {
            it.fill(0)
            throw PortableBackupException("Invalid backup data key")
        }
    }

    private fun authenticateHeader(header: BackupParsedHeader, dataKeyBytes: ByteArray) {
        val plaintext = decrypt(
            SecretKeySpec(dataKeyBytes, "AES"),
            header.authenticationNonce,
            header.withoutAuthentication,
            header.authenticationTag
        )
        try {
            if (plaintext.isNotEmpty()) throw PortableBackupException("Invalid backup header")
        } finally {
            plaintext.fill(0)
        }
    }
}

/** Human-readable, checksum-protected, one-time recovery key. */
object RecoveryKeyCodec {
    fun encode(raw: ByteArray): String {
        require(raw.size == RECOVERY_KEY_BYTES) { "Invalid recovery key length" }
        val checksum = sha256(raw).copyOf(RECOVERY_CHECKSUM_BYTES)
        val combined = concatenate(raw, checksum)
        return try {
            base32Encode(combined).chunked(RECOVERY_GROUP_CHARS).joinToString("-")
        } finally {
            checksum.fill(0)
            combined.fill(0)
        }
    }

    fun decode(encoded: String): ByteArray {
        val normalized = encoded.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        val combined = try {
            base32Decode(normalized)
        } catch (error: IllegalArgumentException) {
            throw PortableBackupException("Recovery key is invalid", error)
        }
        try {
            if (combined.size != RECOVERY_KEY_BYTES + RECOVERY_CHECKSUM_BYTES) {
                throw PortableBackupException("Recovery key is invalid")
            }
            val raw = combined.copyOfRange(0, RECOVERY_KEY_BYTES)
            val expected = sha256(raw).copyOf(RECOVERY_CHECKSUM_BYTES)
            val actual = combined.copyOfRange(RECOVERY_KEY_BYTES, combined.size)
            try {
                if (!MessageDigest.isEqual(expected, actual)) {
                    raw.fill(0)
                    throw PortableBackupException("Recovery key checksum does not match")
                }
                return raw
            } finally {
                expected.fill(0)
                actual.fill(0)
            }
        } finally {
            combined.fill(0)
        }
    }
}

private data class BackupParsedHeader(
    val parameters: BackupKdfParameters,
    val salt: ByteArray,
    val baseHeader: ByteArray,
    val passphraseNonce: ByteArray,
    val passphraseWrappedKey: ByteArray,
    val recoveryNonce: ByteArray?,
    val recoveryWrappedKey: ByteArray?,
    val withoutAuthentication: ByteArray,
    val authenticationNonce: ByteArray,
    val authenticationTag: ByteArray
) {
    fun clear() {
        salt.fill(0)
        baseHeader.fill(0)
        passphraseNonce.fill(0)
        passphraseWrappedKey.fill(0)
        recoveryNonce?.fill(0)
        recoveryWrappedKey?.fill(0)
        withoutAuthentication.fill(0)
        authenticationNonce.fill(0)
        authenticationTag.fill(0)
    }
}

private fun parseHeader(bytes: ByteArray): BackupParsedHeader {
    try {
        val input = DataInputStream(bytes.inputStream())
        val magic = readExact(input, BACKUP_MAGIC.size, "Missing backup magic")
        if (!magic.contentEquals(BACKUP_MAGIC)) throw PortableBackupException("Unknown backup format")
        if (readInt(input, "Missing backup version") != BACKUP_FORMAT_VERSION) {
            throw PortableBackupException("Unsupported backup version")
        }
        val parameters = try {
            BackupKdfParameters(
                iterations = readInt(input, "Missing Argon2 parameters"),
                memoryKibibytes = readInt(input, "Missing Argon2 parameters"),
                parallelism = readInt(input, "Missing Argon2 parameters")
            )
        } catch (error: IllegalArgumentException) {
            throw PortableBackupException("Unsafe Argon2 parameters", error)
        }
        val salt = readExact(input, SALT_BYTES, "Missing backup salt")
        val recoveryFlag = input.read()
        if (recoveryFlag !in 0..1) throw PortableBackupException("Invalid recovery key flag")
        val baseLength = BACKUP_MAGIC.size + Int.SIZE_BYTES * 4 + SALT_BYTES + 1
        val baseHeader = bytes.copyOfRange(0, baseLength)
        val passphraseNonce = readExact(input, NONCE_BYTES, "Missing wrapped backup key")
        val passphraseWrapped = readExact(input, WRAPPED_KEY_BYTES, "Missing wrapped backup key")
        val recoveryNonce = if (recoveryFlag == 1) {
            readExact(input, NONCE_BYTES, "Missing recovery key wrapper")
        } else null
        val recoveryWrapped = if (recoveryFlag == 1) {
            readExact(input, WRAPPED_KEY_BYTES, "Missing recovery key wrapper")
        } else null
        val withoutAuthenticationLength = bytes.size - NONCE_BYTES - GCM_TAG_BYTES
        if (withoutAuthenticationLength < baseLength + NONCE_BYTES + WRAPPED_KEY_BYTES) {
            throw PortableBackupException("Backup header is incomplete")
        }
        val withoutAuthentication = bytes.copyOfRange(0, withoutAuthenticationLength)
        val authenticationNonce = readExact(input, NONCE_BYTES, "Missing header authentication")
        val authenticationTag = readExact(input, GCM_TAG_BYTES, "Missing header authentication")
        if (input.read() != -1) throw PortableBackupException("Unexpected backup header data")
        return BackupParsedHeader(
            parameters,
            salt,
            baseHeader,
            passphraseNonce,
            passphraseWrapped,
            recoveryNonce,
            recoveryWrapped,
            withoutAuthentication,
            authenticationNonce,
            authenticationTag
        )
    } catch (error: PortableBackupException) {
        throw error
    } catch (error: Throwable) {
        throw PortableBackupException("Backup header is damaged", error)
    }
}

private fun buildBaseHeader(
    parameters: BackupKdfParameters,
    salt: ByteArray,
    recoveryEnabled: Boolean
): ByteArray = java.io.ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
        output.write(BACKUP_MAGIC)
        output.writeInt(BACKUP_FORMAT_VERSION)
        output.writeInt(parameters.iterations)
        output.writeInt(parameters.memoryKibibytes)
        output.writeInt(parameters.parallelism)
        output.write(salt)
        output.writeByte(if (recoveryEnabled) 1 else 0)
    }
    bytes.toByteArray()
}

private fun buildHeaderWithoutAuthentication(
    baseHeader: ByteArray,
    passphraseNonce: ByteArray,
    passphraseWrapped: ByteArray,
    recoveryNonce: ByteArray?,
    recoveryWrapped: ByteArray?
): ByteArray = java.io.ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
        output.write(baseHeader)
        output.write(passphraseNonce)
        output.write(passphraseWrapped)
        if (recoveryNonce != null && recoveryWrapped != null) {
            output.write(recoveryNonce)
            output.write(recoveryWrapped)
        }
    }
    bytes.toByteArray()
}

private fun recordHeader(marker: Int, index: Long, length: Int, nonce: ByteArray): ByteArray =
    ByteBuffer.allocate(RECORD_HEADER_BYTES).apply {
        putInt(marker)
        putLong(index)
        putInt(length)
        put(nonce)
    }.array()

private fun trailerHeader(chunkCount: Long, plaintextBytes: Long, nonce: ByteArray): ByteArray =
    ByteBuffer.allocate(TRAILER_HEADER_BYTES).apply {
        putInt(TRAILER_MARKER)
        putLong(chunkCount)
        putLong(plaintextBytes)
        put(nonce)
    }.array()

private fun encrypt(key: SecretKey, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
    Cipher.getInstance(AES_GCM).run {
        init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(aad)
        doFinal(plaintext)
    }

private fun decrypt(key: SecretKey, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
    try {
        Cipher.getInstance(AES_GCM).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(ciphertext)
        }
    } catch (error: AEADBadTagException) {
        throw PortableBackupException("Backup authentication failed", error)
    }

private fun recoveryWrappingKey(rawRecoveryKey: ByteArray): ByteArray {
    val label = "GJR portable recovery v1".toByteArray(Charsets.US_ASCII)
    return try {
        sha256(label, rawRecoveryKey)
    } finally {
        label.fill(0)
    }
}

private fun readExact(input: InputStream, size: Int, message: String): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = input.read(result, offset, size - offset)
        if (count < 0) {
            result.fill(0)
            throw PortableBackupException(message, EOFException(message))
        }
        offset += count
    }
    return result
}

private fun readInt(input: DataInputStream, message: String): Int = try {
    input.readInt()
} catch (error: EOFException) {
    throw PortableBackupException(message, error)
}

private fun readLong(input: DataInputStream, message: String): Long = try {
    input.readLong()
} catch (error: EOFException) {
    throw PortableBackupException(message, error)
}

private fun safeAdd(first: Long, second: Long): Long {
    if (second > Long.MAX_VALUE - first) throw PortableBackupException("Backup size is invalid")
    return first + second
}

private fun sha256(vararg values: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
    values.forEach(::update)
    digest()
}

private fun concatenate(vararg values: ByteArray): ByteArray {
    val size = values.fold(0L) { total, value -> total + value.size }
    require(size <= Int.MAX_VALUE) { "Byte sequence is too large" }
    val output = ByteArray(size.toInt())
    var offset = 0
    values.forEach { value ->
        value.copyInto(output, offset)
        offset += value.size
    }
    return output
}

private fun wipe(buffer: ByteBuffer) {
    val writable = buffer.duplicate()
    writable.clear()
    while (writable.hasRemaining()) writable.put(0)
}

private fun base32Encode(bytes: ByteArray): String {
    val output = StringBuilder((bytes.size * 8 + 4) / 5)
    var accumulator = 0
    var bits = 0
    bytes.forEach { value ->
        accumulator = (accumulator shl 8) or (value.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            output.append(BASE32_ALPHABET[(accumulator ushr bits) and 31])
        }
    }
    if (bits > 0) output.append(BASE32_ALPHABET[(accumulator shl (5 - bits)) and 31])
    return output.toString()
}

private fun base32Decode(value: String): ByteArray {
    val output = java.io.ByteArrayOutputStream((value.length * 5) / 8)
    var accumulator = 0
    var bits = 0
    value.forEach { character ->
        val digit = BASE32_ALPHABET.indexOf(character)
        require(digit >= 0) { "Invalid Base32 character" }
        accumulator = (accumulator shl 5) or digit
        bits += 5
        if (bits >= 8) {
            bits -= 8
            output.write((accumulator ushr bits) and 0xff)
        }
    }
    if (bits > 0 && (accumulator and ((1 shl bits) - 1)) != 0) {
        throw IllegalArgumentException("Invalid Base32 padding bits")
    }
    return output.toByteArray()
}

private val BACKUP_MAGIC = "GJRBAK01".toByteArray(Charsets.US_ASCII)
private const val BACKUP_FORMAT_VERSION = 1
private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
private const val KEY_BYTES = 32
private const val SALT_BYTES = 16
private const val NONCE_BYTES = 12
private const val WRAPPED_KEY_BYTES = KEY_BYTES + GCM_TAG_BYTES
private const val RECOVERY_KEY_BYTES = 32
private const val RECOVERY_CHECKSUM_BYTES = 4
private const val RECOVERY_GROUP_CHARS = 5
private const val RECORD_HEADER_BYTES = Int.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES + NONCE_BYTES
private const val TRAILER_HEADER_BYTES = Int.SIZE_BYTES + Long.SIZE_BYTES + Long.SIZE_BYTES + NONCE_BYTES
private const val CHUNK_MARKER = 0x43484e4b
private const val TRAILER_MARKER = 0x444f4e45
private const val DEFAULT_BACKUP_CHUNK_BYTES = 64 * 1024
private const val MIN_CHUNK_BYTES = 1024
private const val MAX_CHUNK_BYTES = 1024 * 1024
private const val MIN_HEADER_BYTES = 8 + 16 + SALT_BYTES + 1 + NONCE_BYTES + WRAPPED_KEY_BYTES + NONCE_BYTES + GCM_TAG_BYTES
private const val MAX_HEADER_BYTES = MIN_HEADER_BYTES + NONCE_BYTES + WRAPPED_KEY_BYTES
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
