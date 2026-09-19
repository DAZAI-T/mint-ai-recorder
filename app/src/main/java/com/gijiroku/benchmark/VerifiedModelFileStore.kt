package com.gijiroku.benchmark

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class ModelInstallResult(
    val entry: ModelManifestEntry,
    val file: File,
    val alreadyInstalled: Boolean
)

class ModelVerificationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** File-system layer shared by SAF imports and future asset-pack/download installers. */
class VerifiedModelFileStore(private val rootDirectory: File) {
    @Synchronized
    fun install(entry: ModelManifestEntry, input: InputStream): ModelInstallResult {
        ensureRoot()
        val destination = safeDestination(entry)
        if (verifyFile(destination, entry)) {
            input.close()
            return ModelInstallResult(entry, destination, alreadyInstalled = true)
        }
        val partial = File(rootDirectory, ".${entry.fileName}.${UUID.randomUUID()}.partial")
        requireContained(partial)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            input.use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > entry.byteSize) {
                            throw ModelVerificationException(AppLanguage.text("モデルファイルが定義より大きいため拒否しました", "Model file exceeds the expected size"))
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (total != entry.byteSize) {
                throw ModelVerificationException(AppLanguage.text("モデルファイルのサイズが一致しません", "Model file size does not match"))
            }
            val actualSha = digest.digest().toHex()
            if (actualSha != entry.sha256) {
                throw ModelVerificationException(AppLanguage.text("モデルファイルのSHA-256が一致しません", "Model file SHA-256 does not match"))
            }
            moveReplacingAtomically(partial, destination)
            check(verifyFile(destination, entry)) { AppLanguage.text("保存後のモデル検証に失敗しました", "Model verification after saving failed") }
            return ModelInstallResult(entry, destination, alreadyInstalled = false)
        } catch (error: ModelVerificationException) {
            throw error
        } catch (error: Exception) {
            throw ModelVerificationException(AppLanguage.text("モデルを安全に保存できませんでした", "Could not save model securely"), error)
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    fun isPresent(entry: ModelManifestEntry): Boolean {
        val file = safeDestination(entry)
        return file.isFile && file.length() == entry.byteSize
    }

    /** Re-hashes immediately before a runtime receives the path. */
    fun resolveForLoad(entry: ModelManifestEntry): File {
        val file = safeDestination(entry)
        if (!verifyFile(file, entry)) {
            throw ModelVerificationException(AppLanguage.text("モデルが未導入か、改ざん・破損しています: ${entry.modelId}", "Model is missing, modified, or corrupt: ${entry.modelId}"))
        }
        return file
    }

    fun delete(entry: ModelManifestEntry): Boolean {
        val file = safeDestination(entry)
        return !file.exists() || file.delete()
    }

    fun verifyFile(file: File, entry: ModelManifestEntry): Boolean {
        if (!file.isFile || file.length() != entry.byteSize) return false
        requireContained(file)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex() == entry.sha256
    }

    private fun ensureRoot() {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw ModelVerificationException(AppLanguage.text("モデル保存先を作成できません", "Cannot create model storage location"))
        }
        require(rootDirectory.isDirectory) { AppLanguage.text("モデル保存先がディレクトリではありません", "Model storage location is not a directory") }
        requireContained(rootDirectory)
    }

    private fun safeDestination(entry: ModelManifestEntry): File {
        require(SAFE_FILE_NAME.matches(entry.fileName)) { AppLanguage.text("危険なモデルファイル名です", "Unsafe model filename") }
        return File(rootDirectory, entry.fileName).also(::requireContained)
    }

    private fun requireContained(file: File) {
        val root = rootDirectory.canonicalFile
        val candidate = file.canonicalFile
        require(candidate == root || candidate.parentFile == root) { AppLanguage.text("モデル保存先の外部は使用できません", "Cannot use paths outside the model storage location") }
    }

    private fun moveReplacingAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,159}")
        private const val COPY_BUFFER_BYTES = 1024 * 1024
    }
}
