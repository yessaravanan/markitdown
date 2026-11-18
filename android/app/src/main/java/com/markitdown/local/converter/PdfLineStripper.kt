package com.markitdown.local.converter

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter

/**
 * PDFBox text stripper that, instead of writing plain text, records each line with its average
 * font size, boldness and paragraph boundaries so [MarkdownFormatter] can rebuild structure.
 */
class PdfLineStripper : PDFTextStripper() {
    private val lines = mutableListOf<TextLine>()
    private val buffer = StringBuilder()
    private var sizeSum = 0f
    private var chars = 0
    private var boldChars = 0
    private var paragraphStart = true

    init {
        sortByPosition = false
        setShouldSeparateByBeads(true)
    }

    fun extract(document: PDDocument, pageNumber: Int): List<TextLine> {
        lines.clear()
        resetLine()
        paragraphStart = true
        startPage = pageNumber
        endPage = pageNumber
        writeText(document, StringWriter())
        flushLine()
        return lines.toList()
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        buffer.append(text)
        for (tp in textPositions) {
            val u = tp.unicode ?: continue
            if (u.isBlank()) continue
            var size = tp.fontSizeInPt
            if (size <= 0f || size > 500f) size = tp.heightDir
            sizeSum += size
            chars++
            if (isBold(tp)) boldChars++
        }
    }

    override fun writeWordSeparator() {
        buffer.append(' ')
    }

    override fun writeLineSeparator() = flushLine()

    override fun writeParagraphStart() {
        flushLine()
        paragraphStart = true
    }

    override fun writeParagraphEnd() {
        flushLine()
        paragraphStart = true
    }

    override fun writePageStart() = Unit
    override fun writePageEnd() = flushLine()

    private fun flushLine() {
        val text = buffer.toString().trim()
        if (text.isNotEmpty()) {
            lines += TextLine(
                text = text,
                size = if (chars > 0) sizeSum / chars else 0f,
                bold = chars > 0 && boldChars >= chars * 0.8f,
                paragraphStart = paragraphStart,
            )
            paragraphStart = false
        }
        resetLine()
    }

    private fun resetLine() {
        buffer.setLength(0)
        sizeSum = 0f
        chars = 0
        boldChars = 0
    }

    private fun isBold(tp: TextPosition): Boolean {
        val font = tp.font ?: return false
        val name = font.name?.lowercase() ?: ""
        if ("bold" in name || "black" in name || "heavy" in name || "semibold" in name) return true
        val fd = font.fontDescriptor ?: return false
        return fd.isForceBold || fd.fontWeight >= 700f
    }
}
