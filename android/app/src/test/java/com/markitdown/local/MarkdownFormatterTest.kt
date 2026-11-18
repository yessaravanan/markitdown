package com.markitdown.local

import com.markitdown.local.converter.MarkdownFormatter
import com.markitdown.local.converter.PageLines
import com.markitdown.local.converter.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFormatterTest {
    private fun body(t: String, p: Boolean = false) = TextLine(t, 11f, paragraphStart = p)
    private val longLine = "This is a long body line that wraps across the page width of the column"

    @Test
    fun headingsByFontSizeListsAndParagraphs() {
        val md = MarkdownFormatter.format(
            listOf(
                PageLines(
                    listOf(
                        TextLine("Annual Report", 24f, paragraphStart = true),
                        TextLine("Overview", 16f, paragraphStart = true),
                        body(longLine, p = true),
                        body("and continues on the next line of the same paragraph here."),
                        body("• First point"),
                        body("• Second point"),
                        body("2) Numbered thing"),
                        TextLine("Details", 16f, paragraphStart = true),
                        body("Fin.", p = true),
                    ),
                    ocr = false,
                ),
            ),
        )
        assertEquals(
            """
            # Annual Report

            ## Overview

            $longLine and continues on the next line of the same paragraph here.

            - First point
            - Second point
            2. Numbered thing

            ## Details

            Fin.
            """.trimIndent() + "\n",
            md,
        )
    }

    @Test
    fun dehyphenatesAndDropsPageNumbers() {
        val md = MarkdownFormatter.format(
            listOf(
                PageLines(
                    listOf(
                        body("A sentence that has a hyphen-", p = true),
                        body("ated word and another normal hy- phen."),
                        body("Page 3 of 9"),
                    ),
                    ocr = false,
                ),
            ),
        )
        assertEquals("A sentence that has a hyphenated word and another normal hy- phen.\n", md)
    }

    @Test
    fun mergesMasterFormatPartialNumbering() {
        val md = MarkdownFormatter.format(
            listOf(PageLines(listOf(body(".1", p = true), body("The intent of this Request"), body(".2"), body("Available information")), ocr = false)),
        )
        assertTrue(md, md.contains(".1 The intent of this Request"))
        assertTrue(md, md.contains(".2 Available information"))
        assertFalse(md.lines().any { it.trim() == ".1" || it.trim() == ".2" })
    }

    @Test
    fun pageMarkersAndEscaping() {
        val md = MarkdownFormatter.format(
            listOf(
                PageLines(listOf(body("#hashtag text on page one", p = true)), ocr = false),
                PageLines(listOf(TextLine("scanned words", 40f, paragraphStart = true)), ocr = true),
            ),
            pageMarkers = true,
        )
        assertEquals(
            "<!-- Page 1 -->\n\n\\#hashtag text on page one\n\n<!-- Page 2 (OCR) -->\n\nscanned words\n",
            md,
        )
    }
}
