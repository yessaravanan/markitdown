package com.markitdown.local.converter

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** One visual line of text, from either a PDF text layer or OCR. */
data class TextLine(
    val text: String,
    /** PDF: font size in pt. OCR: line height in px. Only compared within one source kind. */
    val size: Float,
    val bold: Boolean = false,
    /** The layout engine thinks a new paragraph starts at this line. */
    val paragraphStart: Boolean = false,
)

data class PageLines(val lines: List<TextLine>, val ocr: Boolean)

/**
 * Turns positioned lines into Markdown: headings from font size, bullet / numbered lists,
 * paragraph re-flow with de-hyphenation, and page-number removal.
 * Pure Kotlin so it can be unit tested on the JVM.
 */
object MarkdownFormatter {

    private val BULLET = Regex("^(?:[•●○◦▪▫■□‣⁃∙·▸►✓✔]\\s*|[-*–—]\\s+)(\\S.*)$")
    private val ORDERED = Regex("^(\\d{1,3})[.)]\\s+(\\S.*)$")
    private val PAGE_NUMBER = Regex(
        "^(?:(?:page|p\\.)\\s*)?\\d{1,4}(?:\\s*(?:of|/)\\s*\\d{1,4})?$|^[-–—]\\s*\\d{1,4}\\s*[-–—]$",
        RegexOption.IGNORE_CASE,
    )

    /** markitdown's MasterFormat fix: a line that is only ".1" belongs to the next line. */
    private val PARTIAL_NUMBERING = Regex("^\\.\\d+$")

    fun format(pages: List<PageLines>, pageMarkers: Boolean = true): String {
        val textStats = SizeStats(pages.filter { !it.ocr }.flatMap { it.lines }, ocr = false)
        val ocrStats = SizeStats(pages.filter { it.ocr }.flatMap { it.lines }, ocr = true)
        val out = StringBuilder()
        pages.forEachIndexed { index, page ->
            val md = formatPage(page.lines, if (page.ocr) ocrStats else textStats)
            if (pageMarkers && pages.size > 1) {
                out.append("<!-- Page ").append(index + 1)
                if (page.ocr) out.append(" (OCR)")
                out.append(" -->\n\n")
            }
            if (md.isNotBlank()) out.append(md).append("\n\n")
        }
        return out.toString().trim() + "\n"
    }

    internal fun formatPage(rawLines: List<TextLine>, stats: SizeStats): String {
        val lines = mergePartialNumbering(dropPageNumbers(rawLines.map { it.copy(text = clean(it.text)) }))
        val bodyLengths = lines.filter { stats.headingLevel(it) == null }.map { it.text.length }.sorted()
        val medianLength = if (bodyLengths.isEmpty()) 0 else bodyLengths[bodyLengths.size / 2]

        val blocks = mutableListOf<Block>()
        var prev: TextLine? = null
        for (line in lines) {
            val text = line.text
            if (text.isEmpty()) {
                blocks.lastOrNull()?.closed = true
                prev = null
                continue
            }
            val last = blocks.lastOrNull()
            val level = stats.headingLevel(line)
            if (level != null) {
                if (last is Block.Heading && last.level == level && !last.closed && prev != null &&
                    stats.headingLevel(prev) == level
                ) {
                    last.text.append(' ').append(text)
                } else {
                    blocks += Block.Heading(level, StringBuilder(text))
                }
                prev = line
                continue
            }

            val bullet = BULLET.find(text)
            val ordered = if (bullet == null) ORDERED.find(text) else null
            when {
                bullet != null -> blocks += Block.ListItem("- ", StringBuilder(bullet.groupValues[1]))
                ordered != null -> blocks += Block.ListItem(
                    "${ordered.groupValues[1]}. ", StringBuilder(ordered.groupValues[2]),
                )
                line.bold && text.length <= 100 && !text.endsWith('.') &&
                    (line.paragraphStart || last !is Block.Paragraph || last.closed) ->
                    blocks += Block.Bold(StringBuilder(text))
                else -> {
                    val prevShort = prev != null && medianLength >= 25 &&
                        prev.text.length < medianLength * 0.6
                    // Layout engines flag hanging indents as new paragraphs; a line that starts
                    // lowercase after a line without closing punctuation is still a wrap.
                    val wrapped = prev != null && text.first().isLowerCase() &&
                        prev.text.last() !in ".!?:;"
                    val continues = last != null && !last.closed && prev != null &&
                        (last is Block.Paragraph || last is Block.ListItem) &&
                        (wrapped || (!line.paragraphStart && !prevShort))
                    if (continues) join(last!!.text, text) else blocks += Block.Paragraph(StringBuilder(text))
                }
            }
            if (blocks.last() is Block.Bold) blocks.last().closed = true
            prev = line
        }
        return render(blocks)
    }

    private fun render(blocks: List<Block>): String {
        val sb = StringBuilder()
        var prev: Block? = null
        for (b in blocks) {
            if (prev != null) {
                // Keep consecutive list items tight; everything else separated by a blank line.
                sb.append(if (prev is Block.ListItem && b is Block.ListItem) "\n" else "\n\n")
            }
            when (b) {
                is Block.Heading -> sb.append("#".repeat(b.level)).append(' ').append(b.text.toString().trim())
                is Block.ListItem -> sb.append(b.marker).append(b.text.toString().trim())
                is Block.Bold -> sb.append("**").append(b.text.toString().trim()).append("**")
                is Block.Paragraph -> sb.append(escapeLeading(b.text.toString().trim()))
            }
            prev = b
        }
        return sb.toString()
    }

    private fun join(sb: StringBuilder, next: String) {
        val n = sb.length
        if (n >= 2 && sb[n - 1] == '-' && sb[n - 2].isLetter() && next.first().isLowerCase()) {
            sb.setLength(n - 1)
            sb.append(next)
        } else {
            sb.append(' ').append(next)
        }
    }

    private fun clean(s: String): String =
        s.replace(' ', ' ').replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .replace(Regex("[ \\t]+"), " ").trim()

    private fun escapeLeading(s: String): String =
        if (s.startsWith("#") || s.startsWith(">") || s.startsWith("+ ") || s.startsWith("=")) "\\$s" else s

    private fun dropPageNumbers(lines: List<TextLine>): List<TextLine> {
        val nonEmpty = lines.indices.filter { lines[it].text.isNotEmpty() }
        if (nonEmpty.size < 2) return lines
        val drop = setOf(nonEmpty.first(), nonEmpty.last()).filter { PAGE_NUMBER.matches(lines[it].text) }.toSet()
        return lines.filterIndexed { i, _ -> i !in drop }
    }

    internal fun mergePartialNumbering(lines: List<TextLine>): List<TextLine> {
        val result = mutableListOf<TextLine>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (PARTIAL_NUMBERING.matches(line.text)) {
                var j = i + 1
                while (j < lines.size && lines[j].text.isEmpty()) j++
                if (j < lines.size) {
                    result += lines[j].copy(text = "${line.text} ${lines[j].text}", paragraphStart = true)
                    i = j + 1
                    continue
                }
            }
            result += line
            i++
        }
        return result
    }

    private sealed class Block(val text: StringBuilder) {
        var closed = false
        class Heading(val level: Int, text: StringBuilder) : Block(text)
        class ListItem(val marker: String, text: StringBuilder) : Block(text)
        class Bold(text: StringBuilder) : Block(text)
        class Paragraph(text: StringBuilder) : Block(text)
    }

    /** Body-size and heading-size statistics for one source kind (PDF text layer vs. OCR). */
    internal class SizeStats(lines: List<TextLine>, private val ocr: Boolean) {
        private val ratio = if (ocr) 1.45f else 1.15f
        private val maxLevels = if (ocr) 2 else 4
        val body: Float
        private val levels: Map<Float, Int>

        init {
            val weights = HashMap<Float, Int>()
            for (l in lines) {
                val chars = l.text.count { !it.isWhitespace() }
                if (chars > 0 && l.size > 0f) weights.merge(quantize(l.size), chars, Int::plus)
            }
            body = weights.maxByOrNull { it.value }?.key ?: 0f
            levels = if (body <= 0f) {
                emptyMap()
            } else {
                lines.filter { isCandidate(it) }.map { quantize(it.size) }.distinct().sortedDescending()
                    .mapIndexed { i, s -> s to minOf(i + 1, maxLevels) }.toMap()
            }
        }

        private fun quantize(size: Float): Float =
            if (ocr) {
                // ~8% buckets: OCR line heights jitter with ascenders/descenders.
                val step = 1.08
                step.pow((ln(size.toDouble()) / ln(step)).roundToInt()).toFloat()
            } else {
                (size * 2).roundToInt() / 2f
            }

        private fun isCandidate(l: TextLine): Boolean {
            val t = l.text.trim()
            if (body <= 0f || l.size < body * ratio) return false
            if (t.length > 150 || t.count { it.isLetter() } < 2) return false
            if (t.split(' ').size > 20) return false
            if (BULLET.matches(t)) return false
            return true
        }

        fun headingLevel(l: TextLine): Int? = if (isCandidate(l)) levels[quantize(l.size)] else null
    }
}
