package com.gijiroku.benchmark

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

object AndroidMeetingPdfWriter {
    fun write(document: MeetingExportDocument, output: OutputStream) {
        val pdf = PdfDocument()
        var page: PdfDocument.Page? = null
        var pageNumber = 0
        var y = PAGE_MARGIN
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = BODY_TEXT_SIZE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        fun startPage() {
            page?.let(pdf::finishPage)
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = PAGE_MARGIN
        }

        fun ensureSpace(lineHeight: Int) {
            if (page == null || y + lineHeight > PAGE_HEIGHT - PAGE_MARGIN) startPage()
        }

        try {
            document.markdown().lineSequence().forEach { markdownLine ->
                val style = styleFor(markdownLine, paint)
                val content = style.text
                if (content.isEmpty()) {
                    ensureSpace(style.lineHeight)
                    y += style.lineHeight / 2
                } else {
                    val availableWidth = PAGE_WIDTH - PAGE_MARGIN * 2 - style.indent
                    wrap(content, availableWidth.toFloat(), paint).forEach { line ->
                        ensureSpace(style.lineHeight)
                        requireNotNull(page).canvas.drawText(
                            line,
                            (PAGE_MARGIN + style.indent).toFloat(),
                            y.toFloat(),
                            paint
                        )
                        y += style.lineHeight
                    }
                }
            }
            page?.let(pdf::finishPage)
            page = null
            pdf.writeTo(output)
            output.flush()
        } finally {
            page?.let { runCatching { pdf.finishPage(it) } }
            pdf.close()
        }
    }

    private fun styleFor(line: String, paint: Paint): PdfLineStyle {
        val result = when {
            line.startsWith("# ") -> PdfLineStyle(line.removePrefix("# "), TITLE_LINE_HEIGHT, 0, TITLE_TEXT_SIZE, true)
            line.startsWith("## ") -> PdfLineStyle(line.removePrefix("## "), HEADING_LINE_HEIGHT, 0, HEADING_TEXT_SIZE, true)
            line.startsWith("### ") -> PdfLineStyle(line.removePrefix("### "), SUBHEADING_LINE_HEIGHT, 0, SUBHEADING_TEXT_SIZE, true)
            line.startsWith("- ") -> PdfLineStyle("• ${line.removePrefix("- ")}", BODY_LINE_HEIGHT, LIST_INDENT, BODY_TEXT_SIZE, false)
            else -> PdfLineStyle(line, BODY_LINE_HEIGHT, 0, BODY_TEXT_SIZE, false)
        }
        paint.textSize = result.textSize
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, if (result.bold) Typeface.BOLD else Typeface.NORMAL)
        return result
    }

    private fun wrap(text: String, maxWidth: Float, paint: Paint): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        text.codePoints().forEach { codePoint ->
            current.appendCodePoint(codePoint)
            if (paint.measureText(current.toString()) > maxWidth && current.codePointCount(0, current.length) > 1) {
                val lastChars = Character.charCount(codePoint)
                current.setLength(current.length - lastChars)
                lines += current.toString()
                current.setLength(0)
                current.appendCodePoint(codePoint)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private data class PdfLineStyle(
        val text: String,
        val lineHeight: Int,
        val indent: Int,
        val textSize: Float,
        val bold: Boolean
    )

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val PAGE_MARGIN = 42
    private const val LIST_INDENT = 12
    private const val TITLE_TEXT_SIZE = 20f
    private const val HEADING_TEXT_SIZE = 16f
    private const val SUBHEADING_TEXT_SIZE = 13f
    private const val BODY_TEXT_SIZE = 11f
    private const val TITLE_LINE_HEIGHT = 30
    private const val HEADING_LINE_HEIGHT = 25
    private const val SUBHEADING_LINE_HEIGHT = 21
    private const val BODY_LINE_HEIGHT = 17
}
