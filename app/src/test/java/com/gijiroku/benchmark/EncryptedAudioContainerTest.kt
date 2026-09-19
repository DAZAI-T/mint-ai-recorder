package com.gijiroku.benchmark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class EncryptedAudioContainerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripPreservesPcmAndMetadataAcrossChunks() {
        val file = temporaryFolder.newFile("meeting.gjra")
        val key = key()
        val samples = ShortArray(257) { index -> (index * 197 - 20_000).toShort() }

        EncryptedAudioWriter(
            file = file,
            key = key,
            metadata = EncryptedAudioMetadata(sampleRate = 16_000, channels = 1),
            chunkPlaintextBytes = 32,
            durableWrites = false
        ).use { writer ->
            writer.appendPcm16(samples, 101)
            writer.appendPcm16(samples.copyOfRange(101, samples.size))
        }

        val (inspection, restored) = EncryptedAudioReader.readAllPcm16(file, key)

        assertArrayEquals(samples, restored)
        assertEquals(16_000, inspection.metadata.sampleRate)
        assertEquals(1, inspection.metadata.channels)
        assertTrue(inspection.finalized)
        assertEquals(samples.size * 2L, inspection.plaintextBytes)
        assertTrue(inspection.chunkCount > 1)
    }

    @Test
    fun authenticatedMetadataIsAvailableBeforePcmChunks() {
        val file = temporaryFolder.newFile("metadata.gjra")
        val key = key()
        EncryptedAudioWriter(
            file = file,
            key = key,
            metadata = EncryptedAudioMetadata(sampleRate = 22_050, channels = 1),
            durableWrites = false
        ).use { it.appendPcm16(shortArrayOf(1, 2)) }
        var metadata: EncryptedAudioMetadata? = null

        EncryptedAudioReader.read(
            file = file,
            key = key,
            onMetadata = { metadata = it }
        ) { assertEquals(22_050, metadata?.sampleRate) }

        assertEquals(22_050, metadata?.sampleRate)
    }

    @Test
    fun emptyRecordingStillHasAuthenticatedFinalTrailer() {
        val file = temporaryFolder.newFile("empty.gjra")
        val key = key()

        EncryptedAudioWriter(file, key, durableWrites = false).use { }

        val (inspection, restored) = EncryptedAudioReader.readAllPcm16(file, key)
        assertTrue(inspection.finalized)
        assertEquals(0, inspection.chunkCount)
        assertEquals(0, restored.size)
    }

    @Test
    fun wrongKeyCannotAuthenticateHeader() {
        val file = temporaryFolder.newFile("wrong-key.gjra")
        EncryptedAudioWriter(file, key(), durableWrites = false).use {
            it.appendPcm16(shortArrayOf(1, 2, 3))
        }

        expectFormatFailure {
            EncryptedAudioReader.read(file, key()) { }
        }
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val file = temporaryFolder.newFile("modified.gjra")
        val key = key()
        EncryptedAudioWriter(
            file,
            key,
            chunkPlaintextBytes = 32,
            durableWrites = false
        ).use {
            it.appendPcm16(ShortArray(40) { value -> value.toShort() })
        }
        RandomAccessFile(file, "rw").use { randomAccess ->
            val ciphertextOffset = ENCRYPTED_AUDIO_HEADER_BYTES + 28L
            randomAccess.seek(ciphertextOffset)
            val original = randomAccess.readByte()
            randomAccess.seek(ciphertextOffset)
            randomAccess.writeByte(original.toInt() xor 0x01)
        }

        expectFormatFailure {
            EncryptedAudioReader.read(file, key) { }
        }
    }

    @Test
    fun incompleteTrailerIsRecoverableButNotFinalized() {
        val file = temporaryFolder.newFile("incomplete.gjra")
        val key = key()
        val samples = ShortArray(100) { it.toShort() }
        EncryptedAudioWriter(
            file,
            key,
            chunkPlaintextBytes = 32,
            durableWrites = false
        ).use { it.appendPcm16(samples) }
        RandomAccessFile(file, "rw").use {
            it.setLength(it.length() - ENCRYPTED_AUDIO_TRAILER_BYTES / 2)
        }

        expectFormatFailure {
            EncryptedAudioReader.read(file, key) { }
        }

        val (inspection, restored) = EncryptedAudioReader.readAllPcm16(
            file,
            key,
            allowIncomplete = true
        )
        assertFalse(inspection.finalized)
        assertArrayEquals(samples, restored)
    }

    @Test
    fun trailingBytesAfterFinalTrailerAreRejected() {
        val file = temporaryFolder.newFile("trailing.gjra")
        val key = key()
        EncryptedAudioWriter(file, key, durableWrites = false).use {
            it.appendPcm16(shortArrayOf(4, 5, 6))
        }
        RandomAccessFile(file, "rw").use {
            it.seek(it.length())
            it.writeByte(7)
        }

        expectFormatFailure {
            EncryptedAudioReader.read(file, key) { }
        }
    }

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    private fun expectFormatFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: EncryptedAudioFormatException) {
            return
        } catch (_: java.io.EOFException) {
            return
        }
        throw AssertionError("Expected encrypted audio format failure")
    }
}
