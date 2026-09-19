package com.gijiroku.benchmark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class ModelManifestEntry(
    val modelId: String,
    val modelFamily: String,
    val upstreamVersion: String,
    val quantization: String,
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val sourceUrl: String,
    val licenseId: String,
    val noticePath: String,
    val compatibleRuntime: String,
    val compatibleProviders: List<String>
)

data class ModelManifest(
    val schemaVersion: Int,
    val catalogVersion: String,
    val models: List<ModelManifestEntry>
) {
    fun requireModel(modelId: String): ModelManifestEntry =
        models.singleOrNull { it.modelId == modelId }
            ?: error(AppLanguage.text("モデル定義が見つかりません: $modelId", "Model definition not found: $modelId"))
}

/** Strict parser for an already signature-verified official model catalog. */
object ModelManifestParser {
    private val rootKeys = setOf("schemaVersion", "catalogVersion", "models")
    private val modelKeys = setOf(
        "modelId", "modelFamily", "upstreamVersion", "quantization", "fileName", "byteSize",
        "sha256", "sourceUrl", "licenseId", "noticePath", "compatibleRuntime",
        "compatibleProviders"
    )
    private val identifier = Regex("[a-z0-9][a-z0-9._-]{0,95}")
    private val fileName = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,159}")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun parse(bytes: ByteArray): ModelManifest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES) { AppLanguage.text("モデル定義のサイズが不正です", "Invalid model definition size") }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        requireExactKeys(root, rootKeys, AppLanguage.text("モデル定義", "Model definition"))
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion == 1) { AppLanguage.text("未対応のモデル定義形式です: $schemaVersion", "Unsupported model definition format: $schemaVersion") }
        val catalogVersion = root.requiredText("catalogVersion", 64)
        val array = root.getJSONArray("models")
        require(array.length() in 1..MAX_MODELS) { AppLanguage.text("モデル数が不正です", "Invalid model count") }
        val models = (0 until array.length()).map { parseEntry(array.getJSONObject(it)) }
        require(models.map { it.modelId }.distinct().size == models.size) { AppLanguage.text("modelIdが重複しています", "Duplicate modelId") }
        require(models.map { it.fileName }.distinct().size == models.size) { AppLanguage.text("fileNameが重複しています", "Duplicate fileName") }
        return ModelManifest(schemaVersion, catalogVersion, models)
    }

    private fun parseEntry(value: JSONObject): ModelManifestEntry {
        requireExactKeys(value, modelKeys, AppLanguage.text("モデル項目", "Model entry"))
        val modelId = value.requiredText("modelId", 96).also { require(identifier.matches(it)) }
        val family = value.requiredText("modelFamily", 64).also { require(identifier.matches(it)) }
        val upstream = value.requiredText("upstreamVersion", 192)
        val quantization = value.requiredText("quantization", 32).also { require(identifier.matches(it)) }
        val acceptedFileName = value.requiredText("fileName", 160).also {
            require(fileName.matches(it) && it != "." && it != "..") { AppLanguage.text("危険なモデルファイル名です", "Unsafe model filename") }
        }
        val byteSize = value.getLong("byteSize").also { require(it in 1..MAX_MODEL_BYTES) }
        val acceptedSha = value.requiredText("sha256", 64).also { require(sha256.matches(it)) }
        val sourceUrl = value.requiredText("sourceUrl", 512).also {
            val uri = URI(it)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                AppLanguage.text("モデル配布元はHTTPSである必要があります", "Model source must use HTTPS")
            }
        }
        val license = value.requiredText("licenseId", 64)
        val notice = value.requiredText("noticePath", 192).also {
            require(it.startsWith("notices/models/") && !it.contains("..") && !it.startsWith('/')) {
                AppLanguage.text("NOTICEパスが不正です", "Invalid NOTICE path")
            }
        }
        val runtime = value.requiredText("compatibleRuntime", 96)
        val providers = value.getJSONArray("compatibleProviders").requiredStrings()
        require(providers.isNotEmpty() && providers.size <= 16 && providers.all(identifier::matches)) {
            AppLanguage.text("対応Provider定義が不正です", "Invalid supported provider definition")
        }
        require(providers.distinct().size == providers.size) { AppLanguage.text("対応Providerが重複しています", "Duplicate supported provider") }
        return ModelManifestEntry(
            modelId, family, upstream, quantization, acceptedFileName, byteSize, acceptedSha,
            sourceUrl, license, notice, runtime, providers
        )
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>, label: String) {
        val actual = value.keys().asSequence().toSet()
        require(actual == expected) { AppLanguage.text("$label の項目が不正です", "Invalid fields in $label") }
    }

    private fun JSONObject.requiredText(key: String, maxLength: Int): String =
        getString(key).also { require(it.isNotBlank() && it.length <= maxLength) { AppLanguage.text("$key が不正です", "Invalid $key") } }

    private fun JSONArray.requiredStrings(): List<String> = (0 until length()).map { index ->
        getString(index).also { require(it.isNotBlank() && it.length <= 96) }
    }

    const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_MODELS = 64
    private const val MAX_MODEL_BYTES = 16L * 1024 * 1024 * 1024
}

/** Detached ECDSA signature verification with an APK-pinned X.509 public key. */
class ModelManifestVerifier(private val publicKey: PublicKey) {
    fun verifyAndParse(manifestBytes: ByteArray, signatureBase64: String): ModelManifest {
        require(manifestBytes.size <= ModelManifestParser.MAX_MANIFEST_BYTES) { AppLanguage.text("モデル定義が大きすぎます", "Model definition is too large") }
        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureBase64.trim()) }
            .getOrElse { throw IllegalArgumentException(AppLanguage.text("モデル定義の署名形式が不正です", "Invalid model signature format"), it) }
        require(signatureBytes.size in 64..80) { AppLanguage.text("モデル定義の署名サイズが不正です", "Invalid model signature size") }
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        require(verifier.verify(signatureBytes)) { AppLanguage.text("モデル定義の署名を確認できません", "Cannot verify model definition signature") }
        return ModelManifestParser.parse(manifestBytes)
    }

    companion object {
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun publicKeyFromBase64(encoded: String): PublicKey {
            val bytes = Base64.getDecoder().decode(encoded.trim())
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }
    }
}

object OfficialModelCatalog {
    const val LIVE_WHISPER_ID = "whisper-small-q5_1"
    const val FINAL_WHISPER_ID = "whisper-large-v3-turbo-q5_0"
    const val SPEAKER_ID = "campplus-voxceleb-16k"
    const val QWEN_SUMMARY_ID = "qwen3-4b-q4_k_m"

    fun load(context: Context): ModelManifest {
        val assets = context.applicationContext.assets
        val manifest = assets.open(MANIFEST_ASSET).use { input ->
            input.readBytesLimited(ModelManifestParser.MAX_MANIFEST_BYTES)
        }
        val signature = assets.open(SIGNATURE_ASSET).bufferedReader().use { it.readText() }
        val publicKey = assets.open(PUBLIC_KEY_ASSET).bufferedReader().use { it.readText() }
        return ModelManifestVerifier(ModelManifestVerifier.publicKeyFromBase64(publicKey))
            .verifyAndParse(manifest, signature)
    }

    private fun java.io.InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { AppLanguage.text("モデル定義が大きすぎます", "Model definition is too large") }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private const val MANIFEST_ASSET = "models/official-models-v1.json"
    private const val SIGNATURE_ASSET = "models/official-models-v1.sig"
    private const val PUBLIC_KEY_ASSET = "models/official-models-v1.pub"
}
