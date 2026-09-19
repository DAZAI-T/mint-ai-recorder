package com.gijiroku.benchmark

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ModelDeleteResult(val filesRemoved: Int, val bytesFreed: Long)

/**
 * Official local-model facade. SAF imports are accepted only when their byte size and SHA-256
 * match the APK-signed catalog, and every path is re-verified before being passed to a runtime.
 */
class ModelStore(context: Context) {

    internal val appContext = context.applicationContext
    private val catalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OfficialModelCatalog.load(appContext) }
    private val verifiedFiles = VerifiedModelFileStore(File(appContext.filesDir, "models/v1"))
    private val localLibrary = LocalModelLibrary(appContext)

    // Read-only fallback for upgrades from the phase-1 file layout. A legacy file is never used
    // unless it matches the exact signed official entry.
    private val legacyLiveModel = File(appContext.filesDir, "live_model.bin")
    private val legacyFinalModel = File(appContext.filesDir, "final_model.bin")
    private val legacySpeakerModel = File(appContext.filesDir, "speaker_model.onnx")

    init {
        syncOfficialLibraryEntries()
    }

    fun hasLiveModel(): Boolean = isPresent(liveEntry(), legacyLiveModel)
    fun liveModelPath(): String = resolveForLoad(liveEntry(), legacyLiveModel).absolutePath
    fun importLiveModel(uri: Uri): ModelInstallResult = import(uri, liveEntry()).also {
        registerOfficialModel(LocalModelLibraryKind.TRANSCRIPTION, it)
    }

    fun hasFinalModel(): Boolean = localLibrary.selected(LocalModelLibraryKind.TRANSCRIPTION) != null ||
        isPresent(finalEntry(), legacyFinalModel)
    fun finalModelPath(): String = localLibrary.selected(LocalModelLibraryKind.TRANSCRIPTION)?.absolutePath
        ?: resolveForLoad(finalEntry(), legacyFinalModel).absolutePath
    fun importFinalModel(uri: Uri): ModelInstallResult = import(uri, finalEntry()).also {
        registerOfficialModel(LocalModelLibraryKind.TRANSCRIPTION, it)
    }

    fun hasSpeakerModel(): Boolean = isPresent(speakerEntry(), legacySpeakerModel)
    fun speakerModelPath(): String = resolveForLoad(speakerEntry(), legacySpeakerModel).absolutePath
    fun importSpeakerModel(uri: Uri): ModelInstallResult = import(uri, speakerEntry())

    fun hasQwenSummaryModel(): Boolean = localLibrary.selected(LocalModelLibraryKind.SUMMARIZATION) != null ||
        isPresent(qwenSummaryEntry(), unsupportedLegacyModel)
    fun qwenSummaryModelPath(): String = localLibrary.selected(LocalModelLibraryKind.SUMMARIZATION)?.absolutePath
        ?: resolveForLoad(qwenSummaryEntry(), unsupportedLegacyModel).absolutePath
    fun importQwenSummaryModel(uri: Uri): ModelInstallResult = import(uri, qwenSummaryEntry()).also {
        registerOfficialModel(LocalModelLibraryKind.SUMMARIZATION, it)
    }

    fun localModels(kind: LocalModelLibraryKind): List<LocalModelRecord> = localLibrary.records(kind)
    fun selectedLocalModel(kind: LocalModelLibraryKind): LocalModelRecord? = localLibrary.selected(kind)
    fun selectLocalModel(kind: LocalModelLibraryKind, id: String) = localLibrary.select(kind, id)
    fun importLocalModel(kind: LocalModelLibraryKind, uri: Uri, name: String? = null): LocalModelInstallResult =
        localLibrary.import(kind, uri, name)
    fun downloadLocalModel(
        kind: LocalModelLibraryKind,
        url: String,
        name: String,
        allowRedirectOrigin: String? = null
    ): LocalModelInstallResult = localLibrary.download(kind, url, name, allowRedirectOrigin)
    fun deleteLocalModel(kind: LocalModelLibraryKind, id: String): ModelDeleteResult = localLibrary.delete(kind, id)

    fun officialCatalogVersion(): String = catalog.catalogVersion

    fun modelEntry(modelId: String): ModelManifestEntry = catalog.requireModel(modelId)

    /** Downloads a signed-catalog recommendation directly from its upstream distributor. */
    fun downloadRecommendedModel(modelId: String): ModelInstallResult {
        val entry = catalog.requireModel(modelId)
        runCatching { resolveForLoad(entry, legacyFileFor(modelId)) }.getOrNull()?.let { existing ->
            return ModelInstallResult(entry, existing, alreadyInstalled = true).also {
                registerRecommendedModelIfSelectable(it)
            }
        }
        val requiredSpace = entry.byteSize + INSTALL_FREE_SPACE_MARGIN_BYTES
        if (appContext.filesDir.usableSpace < requiredSpace) {
            throw ModelVerificationException(
                AppLanguage.text("空き容量が不足しています（${entry.byteSize / 1_000_000} MBに加えて一時保存領域が必要です）", "Not enough space (${entry.byteSize / 1_000_000} MB plus temporary storage required)")
            )
        }

        var current = requireHttpsUrl(entry.sourceUrl)
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) { redirectIndex ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                useCaches = false
            }
            try {
                val status = connection.responseCode
                if (status in DOWNLOAD_REDIRECT_CODES) {
                    if (redirectIndex >= MAX_DOWNLOAD_REDIRECTS) {
                        throw ModelVerificationException(AppLanguage.text("モデル配布元のリダイレクト回数が上限を超えました", "Too many model source redirects"))
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw ModelVerificationException(AppLanguage.text("モデル配布元の応答が不正です", "Invalid model source response"))
                    current = requireHttpsUrl(URL(current, location).toString())
                    return@repeat
                }
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    throw ModelVerificationException(AppLanguage.text("モデルを取得できませんでした（HTTP $status）", "Could not download model (HTTP $status)"))
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > 0L && contentLength != entry.byteSize) {
                    throw ModelVerificationException(AppLanguage.text("配布ファイルの容量が署名済み情報と一致しません", "Download size does not match signed metadata"))
                }
                return verifiedFiles.install(entry, connection.inputStream).also {
                    registerRecommendedModelIfSelectable(it)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw ModelVerificationException(AppLanguage.text("モデルを取得できませんでした", "Could not download model"))
    }

    fun isOfficialModelPresent(modelId: String): Boolean {
        val entry = catalog.requireModel(modelId)
        return isPresent(entry, legacyFileFor(modelId))
    }

    fun hasOfficialModelFile(modelId: String): Boolean {
        val entry = catalog.requireModel(modelId)
        return File(appContext.filesDir, "models/v1/${entry.fileName}").isFile ||
            legacyFileFor(modelId).isFile
    }

    fun verifyOfficialModel(modelId: String): Boolean {
        val entry = catalog.requireModel(modelId)
        return runCatching { resolveForLoad(entry, legacyFileFor(modelId)) }.isSuccess
    }

    /** Deletes model bytes only. Recordings, meeting keys, database rows and artifacts are untouched. */
    fun deleteOfficialModel(modelId: String): ModelDeleteResult {
        val entry = catalog.requireModel(modelId)
        selectableLocalModelKind(modelId)?.let { localLibrary.delete(it, modelId) }
        val targets = listOf(
            File(appContext.filesDir, "models/v1/${entry.fileName}"),
            legacyFileFor(modelId)
        ).distinctBy { it.absolutePath }
        var filesRemoved = 0
        var bytesFreed = 0L
        targets.forEach { target ->
            if (target.exists()) {
                val size = target.length()
                if (!target.delete()) throw ModelVerificationException(AppLanguage.text("モデルファイルを削除できませんでした", "Could not delete model file"))
                filesRemoved += 1
                bytesFreed += size
            }
        }
        return ModelDeleteResult(filesRemoved, bytesFreed)
    }

    private fun import(uri: Uri, entry: ModelManifestEntry): ModelInstallResult {
        runCatching { resolveForLoad(entry, legacyFileFor(entry.modelId)) }.getOrNull()?.let { existing ->
            return ModelInstallResult(entry, existing, alreadyInstalled = true)
        }
        val requiredSpace = entry.byteSize + INSTALL_FREE_SPACE_MARGIN_BYTES
        if (appContext.filesDir.usableSpace < requiredSpace) {
            throw ModelVerificationException(
                AppLanguage.text("空き容量が不足しています（モデル容量に加えて一時保存領域が必要です）", "Not enough space (model size plus temporary storage required)")
            )
        }
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw ModelVerificationException(AppLanguage.text("選択したモデルファイルを開けません", "Cannot open selected model file"))
        return verifiedFiles.install(entry, input)
    }

    private fun isPresent(entry: ModelManifestEntry, legacy: File): Boolean =
        verifiedFiles.isPresent(entry) || (legacy.isFile && legacy.length() == entry.byteSize)

    private fun resolveForLoad(entry: ModelManifestEntry, legacy: File): File {
        runCatching { verifiedFiles.resolveForLoad(entry) }.getOrNull()?.let { return it }
        if (verifiedFiles.verifyFile(legacy, entry)) return legacy
        throw ModelVerificationException(AppLanguage.text("モデルが未導入か、改ざん・破損しています: ${entry.modelId}", "Model is missing, modified, or corrupt: ${entry.modelId}"))
    }

    private fun liveEntry() = catalog.requireModel(OfficialModelCatalog.LIVE_WHISPER_ID)
    private fun finalEntry() = catalog.requireModel(OfficialModelCatalog.FINAL_WHISPER_ID)
    private fun speakerEntry() = catalog.requireModel(OfficialModelCatalog.SPEAKER_ID)
    private fun qwenSummaryEntry() = catalog.requireModel(OfficialModelCatalog.QWEN_SUMMARY_ID)

    private val unsupportedLegacyModel: File
        get() = File(appContext.filesDir, "models/v1/__unsupported__")

    private fun legacyFileFor(modelId: String): File = when (modelId) {
        OfficialModelCatalog.LIVE_WHISPER_ID -> legacyLiveModel
        OfficialModelCatalog.FINAL_WHISPER_ID -> legacyFinalModel
        OfficialModelCatalog.SPEAKER_ID -> legacySpeakerModel
        else -> unsupportedLegacyModel
    }

    private fun syncOfficialLibraryEntries() {
        listOf(
            OfficialModelCatalog.LIVE_WHISPER_ID,
            OfficialModelCatalog.FINAL_WHISPER_ID,
            OfficialModelCatalog.QWEN_SUMMARY_ID
        ).forEach { modelId ->
            selectableLocalModelKind(modelId)?.let { kind ->
                registerExistingOfficial(kind, catalog.requireModel(modelId), legacyFileFor(modelId))
            }
        }
    }

    private fun registerExistingOfficial(
        kind: LocalModelLibraryKind,
        entry: ModelManifestEntry,
        legacy: File
    ) {
        val primary = File(appContext.filesDir, "models/v1/${entry.fileName}")
        val file = listOf(primary, legacy).firstOrNull { it.isFile && it.length() == entry.byteSize } ?: return
        localLibrary.registerExisting(
            kind = kind,
            id = entry.modelId,
            displayName = entry.upstreamVersion,
            file = file,
            sha256 = entry.sha256,
            source = entry.sourceUrl
        )
    }

    private fun registerOfficialModel(kind: LocalModelLibraryKind, result: ModelInstallResult) {
        localLibrary.registerExisting(
            kind = kind,
            id = result.entry.modelId,
            displayName = result.entry.upstreamVersion,
            file = result.file,
            sha256 = result.entry.sha256,
            source = result.entry.sourceUrl
        )
        localLibrary.select(kind, result.entry.modelId)
    }

    private fun registerRecommendedModelIfSelectable(result: ModelInstallResult) {
        selectableLocalModelKind(result.entry.modelId)?.let { kind -> registerOfficialModel(kind, result) }
    }

    private fun requireHttpsUrl(value: String): URL {
        val url = runCatching { URL(value) }
            .getOrElse { throw ModelVerificationException(AppLanguage.text("推奨モデルの配布URLが不正です", "Invalid recommended model URL")) }
        if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null || url.port !in listOf(-1, 443)) {
            throw ModelVerificationException(AppLanguage.text("推奨モデルはHTTPSの標準ポートからのみ取得できます", "Recommended models require the standard HTTPS port"))
        }
        return url
    }

    companion object {
        private const val INSTALL_FREE_SPACE_MARGIN_BYTES = 256L * 1024 * 1024
        private const val MAX_DOWNLOAD_REDIRECTS = 5
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        private val DOWNLOAD_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Maps trusted recommendation IDs to the local processing stages that can use them. */
internal fun selectableLocalModelKind(modelId: String): LocalModelLibraryKind? = when (modelId) {
    OfficialModelCatalog.LIVE_WHISPER_ID,
    OfficialModelCatalog.FINAL_WHISPER_ID -> LocalModelLibraryKind.TRANSCRIPTION
    OfficialModelCatalog.QWEN_SUMMARY_ID -> LocalModelLibraryKind.SUMMARIZATION
    else -> null
}
