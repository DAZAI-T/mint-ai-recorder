package com.gijiroku.benchmark

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

enum class LocalModelLibraryKind(val extension: String) {
    TRANSCRIPTION(".bin"),
    SUMMARIZATION(".gguf");

    val displayName: String get() = when (this) {
        TRANSCRIPTION -> AppLanguage.text("音声認識", "Transcription")
        SUMMARIZATION -> AppLanguage.text("要約", "Summary")
    }
}

data class LocalModelRecord(
    val id: String,
    val displayName: String,
    val kind: LocalModelLibraryKind,
    val absolutePath: String,
    val byteSize: Long,
    val sha256: String,
    val source: String,
    val managed: Boolean
)

data class LocalModelInstallResult(val record: LocalModelRecord, val alreadyInstalled: Boolean)

class ModelCrossOriginRedirectException(val destination: URL) : Exception()

/** App-private model catalog. Model files are data only; executable Provider code is never imported. */
class LocalModelLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val root = File(appContext.filesDir, "models/user")

    fun records(kind: LocalModelLibraryKind): List<LocalModelRecord> = readRecords()
        .filter { it.kind == kind && isUsablePath(it) }
        .sortedBy { it.displayName.lowercase() }

    fun selected(kind: LocalModelLibraryKind): LocalModelRecord? {
        val id = prefs.getString(selectedKey(kind), null) ?: return null
        return records(kind).firstOrNull { it.id == id }
    }

    fun select(kind: LocalModelLibraryKind, id: String) {
        require(records(kind).any { it.id == id }) { AppLanguage.text("登録されていないローカルモデルです", "Local model is not registered") }
        prefs.edit().putString(selectedKey(kind), id).apply()
    }

    fun import(kind: LocalModelLibraryKind, uri: Uri, displayName: String? = null): LocalModelInstallResult {
        val sourceName = queryDisplayName(uri) ?: "model${kind.extension}"
        val name = displayName?.trim().takeUnless { it.isNullOrEmpty() } ?: sourceName
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw ModelVerificationException(AppLanguage.text("選択したモデルファイルを開けません", "Cannot open selected model file"))
        return install(kind, name, sourceName, AppLanguage.text("端末内ファイル", "Device file"), input)
    }

    fun download(
        kind: LocalModelLibraryKind,
        sourceUrl: String,
        displayName: String,
        allowRedirectOrigin: String? = null
    ): LocalModelInstallResult {
        var current = validateHttpsUrl(sourceUrl)
        val originalOrigin = origin(current)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirectIndex >= MAX_REDIRECTS) {
                        throw ModelVerificationException(AppLanguage.text("モデルのリダイレクト回数が上限を超えました", "Too many model redirects"))
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw ModelVerificationException(AppLanguage.text("モデル配布先のリダイレクトが不正です", "Invalid model download redirect"))
                    val next = validateHttpsUrl(URL(current, location).toString())
                    val nextOrigin = origin(next)
                    if (nextOrigin != originalOrigin && nextOrigin != allowRedirectOrigin) {
                        throw ModelCrossOriginRedirectException(next)
                    }
                    current = next
                    return@repeat
                }
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    throw ModelVerificationException(AppLanguage.text("モデルを取得できませんでした（HTTP $status）", "Could not download model (HTTP $status)"))
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > 0L) {
                    requireModelSize(contentLength)
                    if (appContext.filesDir.usableSpace < contentLength + MIN_FREE_SPACE_AFTER_INSTALL) {
                        throw ModelVerificationException(AppLanguage.text("モデルを保存する空き容量が不足しています", "Not enough space to save model"))
                    }
                }
                val sourceName = current.path.substringAfterLast('/').ifBlank { "model${kind.extension}" }
                return install(kind, displayName, sourceName, safeSourceUrl(current), connection.inputStream)
            } finally {
                connection.disconnect()
            }
        }
        throw ModelVerificationException(AppLanguage.text("モデルを取得できませんでした", "Could not download model"))
    }

    fun registerExisting(
        kind: LocalModelLibraryKind,
        id: String,
        displayName: String,
        file: File,
        sha256: String,
        source: String
    ) {
        if (!file.isFile || readRecords().any { it.id == id }) return
        val record = LocalModelRecord(
            id = id,
            displayName = displayName,
            kind = kind,
            absolutePath = file.absolutePath,
            byteSize = file.length(),
            sha256 = sha256,
            source = source,
            managed = false
        )
        writeRecords(readRecords() + record)
        if (selected(kind) == null) select(kind, id)
    }

    fun delete(kind: LocalModelLibraryKind, id: String): ModelDeleteResult {
        val all = readRecords()
        val record = all.firstOrNull { it.kind == kind && it.id == id }
            ?: return ModelDeleteResult(0, 0)
        var removed = 0
        var freed = 0L
        if (record.managed) {
            val file = File(record.absolutePath)
            if (file.isFile) {
                require(isInsideRoot(file)) { AppLanguage.text("モデル保存先が不正です", "Invalid model storage location") }
                freed = file.length()
                if (!file.delete()) throw ModelVerificationException(AppLanguage.text("モデルファイルを削除できませんでした", "Could not delete model file"))
                removed = 1
            }
        }
        writeRecords(all.filterNot { it.kind == kind && it.id == id })
        if (prefs.getString(selectedKey(kind), null) == id) prefs.edit().remove(selectedKey(kind)).apply()
        return ModelDeleteResult(removed, freed)
    }

    private fun install(
        kind: LocalModelLibraryKind,
        displayName: String,
        sourceName: String,
        source: String,
        input: InputStream
    ): LocalModelInstallResult {
        LocalModelFileValidator.requireCompatibleName(kind, sourceName)
        val acceptedName = displayName.trim().takeIf { it.isNotEmpty() && it.length <= 120 }
            ?: throw ModelVerificationException(AppLanguage.text("モデルの表示名が不正です", "Invalid model display name"))
        root.mkdirs()
        val temp = File(root, ".${UUID.randomUUID()}.part")
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(4)
        var headerCount = 0
        try {
            BufferedInputStream(input).use { sourceInput ->
                FileOutputStream(temp).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = sourceInput.read(buffer)
                        if (count < 0) break
                        if (headerCount < header.size) {
                            val copied = minOf(header.size - headerCount, count)
                            buffer.copyInto(header, headerCount, 0, copied)
                            headerCount += copied
                        }
                        total += count
                        requireModelSize(total)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    buffer.fill(0)
                }
            }
            LocalModelFileValidator.requireCompatibleHeader(kind, header, headerCount)
            if (appContext.filesDir.usableSpace < MIN_FREE_SPACE_AFTER_INSTALL) {
                throw ModelVerificationException(AppLanguage.text("モデル導入後の空き容量が不足します", "Not enough free space after installation"))
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            readRecords().firstOrNull { it.kind == kind && it.sha256 == hash }?.let {
                temp.delete()
                select(kind, it.id)
                return LocalModelInstallResult(it, alreadyInstalled = true)
            }
            val id = UUID.randomUUID().toString()
            val destination = File(root, "$id${kind.extension}")
            if (!temp.renameTo(destination)) throw ModelVerificationException(AppLanguage.text("モデルを保存できませんでした", "Could not save model"))
            val record = LocalModelRecord(
                id, acceptedName, kind, destination.absolutePath, total, hash, source, managed = true
            )
            writeRecords(readRecords() + record)
            select(kind, id)
            return LocalModelInstallResult(record, alreadyInstalled = false)
        } catch (error: Exception) {
            temp.delete()
            if (error is ModelVerificationException || error is ModelCrossOriginRedirectException) throw error
            throw ModelVerificationException(error.message ?: AppLanguage.text("モデルを導入できませんでした", "Could not install model"))
        } finally {
            header.fill(0)
        }
    }

    private fun readRecords(): List<LocalModelRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.getJSONObject(index)
                    add(LocalModelRecord(
                        id = value.getString("id"),
                        displayName = value.getString("displayName"),
                        kind = LocalModelLibraryKind.valueOf(value.getString("kind")),
                        absolutePath = value.getString("absolutePath"),
                        byteSize = value.getLong("byteSize"),
                        sha256 = value.getString("sha256"),
                        source = value.optString("source"),
                        managed = value.optBoolean("managed", true)
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeRecords(records: List<LocalModelRecord>) {
        val array = JSONArray()
        records.forEach { record -> array.put(JSONObject()
            .put("id", record.id)
            .put("displayName", record.displayName)
            .put("kind", record.kind.name)
            .put("absolutePath", record.absolutePath)
            .put("byteSize", record.byteSize)
            .put("sha256", record.sha256)
            .put("source", record.source)
            .put("managed", record.managed))
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun isUsablePath(record: LocalModelRecord): Boolean {
        val file = File(record.absolutePath)
        return file.isFile && file.length() == record.byteSize && (!record.managed || isInsideRoot(file))
    }

    private fun isInsideRoot(file: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        return file.canonicalFile.toPath().startsWith(rootPath)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun validateHttpsUrl(value: String): URL {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            AppLanguage.text("モデルURLはHTTPSで入力してください", "Enter an HTTPS model URL")
        }
        return uri.toURL()
    }

    private fun origin(url: URL): String = "${url.protocol}://${url.host}:${if (url.port == -1) 443 else url.port}"

    private fun safeSourceUrl(url: URL): String = URI(
        url.protocol,
        null,
        url.host,
        url.port,
        url.path,
        null,
        null
    ).toString()

    private fun requireModelSize(bytes: Long) {
        if (bytes <= 0L || bytes > MAX_MODEL_BYTES) {
            throw ModelVerificationException(AppLanguage.text("モデル容量が不正または上限を超えています", "Invalid model size or size limit exceeded"))
        }
    }

    private fun selectedKey(kind: LocalModelLibraryKind) = "selected_${kind.name.lowercase()}"

    companion object {
        private const val PREFS_NAME = "gijiroku_local_model_library"
        private const val KEY_RECORDS = "records_v1"
        private const val MAX_MODEL_BYTES = 16L * 1024 * 1024 * 1024
        private const val MIN_FREE_SPACE_AFTER_INSTALL = 256L * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 10 * 60_000
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

object LocalModelFileValidator {
    fun requireCompatibleName(kind: LocalModelLibraryKind, fileName: String) {
        require(fileName.lowercase().endsWith(kind.extension)) {
            AppLanguage.text("${kind.displayName}モデルは${kind.extension}形式を選択してください", "Choose a ${kind.extension} file for ${kind.displayName}")
        }
    }

    fun requireCompatibleHeader(kind: LocalModelLibraryKind, header: ByteArray, count: Int) {
        require(count == 4) { AppLanguage.text("モデルファイルが短すぎます", "Model file is too short") }
        val valid = when (kind) {
            // whisper.cpp's legacy GGML file magic (0x67676d6c, little endian).
            LocalModelLibraryKind.TRANSCRIPTION -> header.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))
            LocalModelLibraryKind.SUMMARIZATION -> header.contentEquals("GGUF".toByteArray(Charsets.US_ASCII))
        }
        require(valid) { AppLanguage.text("対応ランタイムのモデル形式を確認できません", "Cannot verify model format for this runtime") }
    }
}
