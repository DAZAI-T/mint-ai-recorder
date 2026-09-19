package com.gijiroku.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingExportInstrumentedTest {

    @Test
    fun markdownAndPdfAreStreamedThroughContentProviderWithoutPlaintextCacheFiles() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val document = MeetingExportDocument(
            title = "議事録",
            createdAtLabel = "2026-09-08 12:00:00 JST",
            speakerCountLabel = "推定話者数: 2人",
            summary = ExportSummary(
                purpose = "共有試験",
                decisions = listOf("一時ファイルを作らない"),
                actionItems = emptyList(),
                openQuestions = emptyList(),
                summary = "PDFとMarkdownをパイプで共有する。"
            ),
            transcript = "話者A: こんにちは。\n話者B: 確認しました。"
        )
        val filesBefore = context.cacheDir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("md", "pdf") }
            .map { it.absolutePath }
            .toSet()

        val markdownUri = MeetingExportStreamRegistry.prepare(context, document, MeetingExportFormat.MARKDOWN)
        val markdown = context.contentResolver.openInputStream(markdownUri)!!.use { it.readBytes() }
        assertArrayEquals(document.markdown().toByteArray(Charsets.UTF_8), markdown)

        val pdfUri = MeetingExportStreamRegistry.prepare(context, document, MeetingExportFormat.PDF)
        val pdf = context.contentResolver.openInputStream(pdfUri)!!.use { it.readBytes() }
        assertTrue(pdf.size > 500)
        assertTrue(pdf.copyOfRange(0, 4).contentEquals("%PDF".toByteArray(Charsets.US_ASCII)))

        val filesAfter = context.cacheDir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("md", "pdf") }
            .map { it.absolutePath }
            .toSet()
        assertTrue(filesAfter == filesBefore)
    }
}
