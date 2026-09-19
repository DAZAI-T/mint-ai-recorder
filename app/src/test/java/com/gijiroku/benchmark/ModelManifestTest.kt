package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ModelManifestTest {
    @Test
    fun bundledCatalogFiles_verifyWithBundledPublicKey() {
        val assets = File("src/main/assets")
        val manifestBytes = File(assets, "models/official-models-v1.json").readBytes()
        val signature = File(assets, "models/official-models-v1.sig").readText()
        val publicKey = File(assets, "models/official-models-v1.pub").readText()

        val catalog = ModelManifestVerifier(ModelManifestVerifier.publicKeyFromBase64(publicKey))
            .verifyAndParse(manifestBytes, signature)

        assertEquals(4, catalog.models.size)
        val qwen = catalog.requireModel(OfficialModelCatalog.QWEN_SUMMARY_ID)
        assertEquals(2_497_280_256L, qwen.byteSize)
        assertEquals("llama.cpp-b10516", qwen.compatibleRuntime)
        catalog.models.forEach { assertTrue(File(assets, it.noticePath).isFile) }
    }

    @Test
    fun signatureAndStrictSchema_acceptValidManifest() {
        val bytes = validManifest().toByteArray()
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keys.private)
            update(bytes)
        }

        val parsed = ModelManifestVerifier(keys.public)
            .verifyAndParse(bytes, Base64.getEncoder().encodeToString(signer.sign()))

        assertEquals("test-v1", parsed.catalogVersion)
        assertEquals("test-model", parsed.models.single().modelId)
    }

    @Test
    fun signature_rejectsOneByteMutation() {
        val bytes = validManifest().toByteArray()
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keys.private)
            update(bytes)
        }
        val signature = Base64.getEncoder().encodeToString(signer.sign())
        bytes[bytes.lastIndex - 2] = (bytes[bytes.lastIndex - 2].toInt() xor 1).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            ModelManifestVerifier(keys.public).verifyAndParse(bytes, signature)
        }
    }

    @Test
    fun strictSchema_rejectsUnknownFieldAndUnsafeFileName() {
        val unknown = validManifest().replace("\"models\":", "\"unexpected\":true,\"models\":")
        assertThrows(IllegalArgumentException::class.java) {
            ModelManifestParser.parse(unknown.toByteArray())
        }
        val unsafe = validManifest().replace("model.bin", "../model.bin")
        assertThrows(IllegalArgumentException::class.java) {
            ModelManifestParser.parse(unsafe.toByteArray())
        }
    }

    private fun validManifest() = """
        {
          "schemaVersion": 1,
          "catalogVersion": "test-v1",
          "models": [{
            "modelId": "test-model",
            "modelFamily": "whisper",
            "upstreamVersion": "test@1",
            "quantization": "q5_1",
            "fileName": "model.bin",
            "byteSize": 3,
            "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "sourceUrl": "https://example.com/model.bin",
            "licenseId": "MIT",
            "noticePath": "notices/models/test.md",
            "compatibleRuntime": "test-runtime",
            "compatibleProviders": ["local.test"]
          }]
        }
    """.trimIndent()
}
