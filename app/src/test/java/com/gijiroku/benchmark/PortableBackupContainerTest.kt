package com.gijiroku.benchmark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class PortableBackupContainerTest {

    private val keyDeriver = BackupKeyDeriver { passphrase, salt, parameters ->
        MessageDigest.getInstance("SHA-256").run {
            update(String(passphrase).toByteArray(Charsets.UTF_8))
            update(salt)
            update(parameters.iterations.toString().toByteArray())
            update(parameters.memoryKibibytes.toString().toByteArray())
            update(parameters.parallelism.toString().toByteArray())
            digest()
        }
    }

    @Test
    fun passphraseRoundTripStreamsMultipleAuthenticatedChunks() {
        val plaintext = ByteArray(5_003) { ((it * 31) and 0xff).toByte() }
        val created = createBackup(plaintext, enableRecovery = false)
        val restored = ByteArrayOutputStream()

        val inspection = PortableBackupReader.read(
            created.bytes.inputStream(),
            BackupCredential.Passphrase("correct horse battery staple".toCharArray()),
            keyDeriver,
            restored::write
        )

        assertArrayEquals(plaintext, restored.toByteArray())
        assertEquals(5_003L, inspection.plaintextBytes)
        assertEquals(5L, inspection.chunkCount)
        assertFalse(inspection.usedRecoveryKey)
        assertEquals(BackupKdfParameters(), inspection.kdfParameters)
    }

    @Test
    fun recoveryKeyRestoresWithoutPassphraseAndHasChecksum() {
        val plaintext = "portable secret meeting".repeat(100).toByteArray()
        val created = createBackup(plaintext, enableRecovery = true)
        assertNotNull(created.recoveryKey)
        val recoveryKey = created.recoveryKey!!
        val restored = ByteArrayOutputStream()

        val inspection = PortableBackupReader.read(
            created.bytes.inputStream(),
            BackupCredential.RecoveryKey(recoveryKey.lowercase()),
            keyDeriver,
            restored::write
        )

        assertArrayEquals(plaintext, restored.toByteArray())
        assertTrue(inspection.usedRecoveryKey)
        val changed = recoveryKey.toCharArray().also { characters ->
            val index = characters.indexOfFirst { it != '-' }
            characters[index] = if (characters[index] == 'A') 'B' else 'A'
        }.concatToString()
        assertThrows(PortableBackupException::class.java) { RecoveryKeyCodec.decode(changed) }
    }

    @Test
    fun wrongPassphraseDoesNotReleasePlaintext() {
        val created = createBackup("classified".toByteArray(), enableRecovery = false)
        var callbacks = 0

        assertThrows(PortableBackupException::class.java) {
            PortableBackupReader.read(
                created.bytes.inputStream(),
                BackupCredential.Passphrase("wrong password".toCharArray()),
                keyDeriver
            ) { callbacks++ }
        }

        assertEquals(0, callbacks)
    }

    @Test
    fun tamperedHeaderAndChunkAreRejected() {
        val plaintext = ByteArray(2_500) { it.toByte() }
        val created = createBackup(plaintext, enableRecovery = true)

        val badHeader = created.bytes.clone().also { it[24] = (it[24].toInt() xor 1).toByte() }
        assertThrows(PortableBackupException::class.java) {
            PortableBackupReader.read(
                badHeader.inputStream(),
                BackupCredential.RecoveryKey(created.recoveryKey!!),
                keyDeriver
            ) {}
        }

        val badChunk = created.bytes.clone().also { it[it.size - 60] = (it[it.size - 60].toInt() xor 1).toByte() }
        assertThrows(PortableBackupException::class.java) {
            PortableBackupReader.read(
                badChunk.inputStream(),
                BackupCredential.Passphrase("correct horse battery staple".toCharArray()),
                keyDeriver
            ) {}
        }
    }

    @Test
    fun truncatedAndTrailingBackupsAreRejected() {
        val created = createBackup(ByteArray(4_000) { it.toByte() }, enableRecovery = false)
        val truncated = created.bytes.copyOf(created.bytes.size - 8)
        val trailing = created.bytes + byteArrayOf(1)

        listOf(truncated, trailing).forEach { invalid ->
            assertThrows(PortableBackupException::class.java) {
                PortableBackupReader.read(
                    invalid.inputStream(),
                    BackupCredential.Passphrase("correct horse battery staple".toCharArray()),
                    keyDeriver
                ) {}
            }
        }
    }

    private fun createBackup(plaintext: ByteArray, enableRecovery: Boolean): CreatedBackup {
        val output = ByteArrayOutputStream()
        val writer = PortableBackupWriter(
            output = output,
            passphrase = "correct horse battery staple".toCharArray(),
            keyDeriver = keyDeriver,
            enableRecoveryKey = enableRecovery,
            chunkSize = 1_024
        )
        writer.write(plaintext)
        val result = writer.finish()
        return CreatedBackup(output.toByteArray(), result.recoveryKey)
    }

    private data class CreatedBackup(val bytes: ByteArray, val recoveryKey: String?)
}
