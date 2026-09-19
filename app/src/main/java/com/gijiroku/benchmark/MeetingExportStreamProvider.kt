package com.gijiroku.benchmark

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

enum class MeetingExportFormat(val mimeType: String, val extension: String) {
    MARKDOWN("text/markdown", "md"),
    PDF("application/pdf", "pdf")
}

object MeetingExportStreamRegistry {
    private data class PendingExport(
        val document: MeetingExportDocument,
        val format: MeetingExportFormat,
        val filename: String,
        val expiresAtElapsedMs: Long
    )

    private val pending = ConcurrentHashMap<String, PendingExport>()

    fun prepare(context: Context, document: MeetingExportDocument, format: MeetingExportFormat): Uri {
        removeExpired()
        val token = UUID.randomUUID().toString()
        val filename = "gijiroku-share-${System.currentTimeMillis()}.${format.extension}"
        pending[token] = PendingExport(
            document,
            format,
            filename,
            android.os.SystemClock.elapsedRealtime() + EXPORT_LIFETIME_MS
        )
        return Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.exports")
            .appendPath(token)
            .build()
    }

    internal fun peek(uri: Uri): ExportDescriptor? {
        removeExpired()
        val token = uri.pathSegments.singleOrNull() ?: return null
        return pending[token]?.let { ExportDescriptor(it.format.mimeType, it.filename) }
    }

    internal fun payload(uri: Uri): ExportPayload? {
        removeExpired()
        val token = uri.pathSegments.singleOrNull() ?: return null
        return pending[token]?.let { ExportPayload(it.document, it.format) }
    }

    private fun removeExpired() {
        val now = android.os.SystemClock.elapsedRealtime()
        pending.entries.removeIf { it.value.expiresAtElapsedMs < now }
    }

    internal data class ExportDescriptor(val mimeType: String, val filename: String)
    internal data class ExportPayload(val document: MeetingExportDocument, val format: MeetingExportFormat)

    private const val EXPORT_LIFETIME_MS = 10 * 60 * 1000L
}

/** Serves an explicitly requested share through a pipe; no plaintext cache file is created. */
class MeetingExportStreamProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = MeetingExportStreamRegistry.peek(uri)?.mimeType

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Export stream is read-only")
        val payload = MeetingExportStreamRegistry.payload(uri)
            ?: throw java.io.FileNotFoundException("Export expired")
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "meeting-export-writer") {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    when (payload.format) {
                        MeetingExportFormat.MARKDOWN -> payload.document.writeMarkdown(output)
                        MeetingExportFormat.PDF -> AndroidMeetingPdfWriter.write(payload.document, output)
                    }
                }
            }
        }
        return pipe[0]
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val descriptor = MeetingExportStreamRegistry.peek(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            val row = newRow()
            columns.forEach { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> row.add(descriptor.filename)
                    OpenableColumns.SIZE -> row.add(null)
                    else -> row.add(null)
                }
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        fun shareClip(context: Context, uri: Uri): ClipData =
            ClipData.newUri(context.contentResolver, AppLanguage.text("議事録", "Minutes"), uri)
    }
}
