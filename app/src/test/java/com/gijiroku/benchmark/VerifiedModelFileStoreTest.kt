package com.gijiroku.benchmark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class VerifiedModelFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installVerifiesAndResolveRehashesBeforeLoad() {
        val bytes = "verified model".toByteArray()
        val entry = entry(bytes)
        val store = VerifiedModelFileStore(temporaryFolder.newFolder("models"))

        val installed = store.install(entry, ByteArrayInputStream(bytes))

        assertFalse(installed.alreadyInstalled)
        assertArrayEquals(bytes, store.resolveForLoad(entry).readBytes())
        installed.file.writeBytes("tampered model".toByteArray()) // same byte length
        assertThrows(ModelVerificationException::class.java) { store.resolveForLoad(entry) }
    }

    @Test
    fun rejectsWrongHashTruncationAndExcessWithoutLeavingPartialFile() {
        val bytes = "expected".toByteArray()
        val root = temporaryFolder.newFolder("models")
        val store = VerifiedModelFileStore(root)

        assertThrows(ModelVerificationException::class.java) {
            store.install(entry(bytes), ByteArrayInputStream("mismatch".toByteArray()))
        }
        assertThrows(ModelVerificationException::class.java) {
            store.install(entry(bytes), ByteArrayInputStream("short".toByteArray()))
        }
        assertThrows(ModelVerificationException::class.java) {
            store.install(entry(bytes), ByteArrayInputStream("too many bytes".toByteArray()))
        }

        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".partial") })
        assertFalse(store.isPresent(entry(bytes)))
    }

    @Test
    fun refusesPathTraversal() {
        val bytes = byteArrayOf(1, 2, 3)
        val unsafe = entry(bytes).copy(fileName = "../outside.bin")
        val store = VerifiedModelFileStore(temporaryFolder.newFolder("models"))

        assertThrows(IllegalArgumentException::class.java) {
            store.install(unsafe, ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun validExistingInstallIsNotOverwritten() {
        val bytes = "official".toByteArray()
        val store = VerifiedModelFileStore(temporaryFolder.newFolder("models"))
        val entry = entry(bytes)
        store.install(entry, ByteArrayInputStream(bytes))

        val second = store.install(entry, ByteArrayInputStream("attacker".toByteArray()))

        assertTrue(second.alreadyInstalled)
        assertArrayEquals(bytes, second.file.readBytes())
    }

    @Test
    fun deleteRemovesOnlyTheSelectedModelFile() {
        val root = temporaryFolder.newFolder("models")
        val store = VerifiedModelFileStore(root)
        val selectedBytes = "selected".toByteArray()
        val selected = entry(selectedBytes)
        val unrelated = java.io.File(root, "meeting-data.gjraud").apply {
            writeText("must remain")
        }
        store.install(selected, ByteArrayInputStream(selectedBytes))

        assertTrue(store.delete(selected))

        assertFalse(store.isPresent(selected))
        assertTrue(unrelated.isFile)
        assertTrue(unrelated.readText() == "must remain")
    }

    private fun entry(bytes: ByteArray) = ModelManifestEntry(
        modelId = "test-model",
        modelFamily = "test",
        upstreamVersion = "test@1",
        quantization = "fp32",
        fileName = "model.bin",
        byteSize = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        sourceUrl = "https://example.com/model.bin",
        licenseId = "MIT",
        noticePath = "notices/models/test.md",
        compatibleRuntime = "test",
        compatibleProviders = listOf("local.test")
    )

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
