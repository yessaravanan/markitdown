package com.markitdown.local.converter

import android.content.Context
import com.markitdown.local.ocr.OcrEngine

/**
 * Entry point, analogous to markitdown's `MarkItDown.convert()`: picks the first converter
 * that accepts each source and stitches the results into one Markdown document.
 */
class MarkItDown(private val context: Context) {

    suspend fun convert(
        sources: List<SourceDocument>,
        options: ConversionOptions,
        progress: ProgressListener,
    ): DocumentConverterResult = OcrEngine(context).use { ocr ->
        val converters = listOf(PdfConverter(ocr), ImageConverter(ocr))
        val parts = mutableListOf<String>()
        var title: String? = null
        sources.forEachIndexed { i, source ->
            val converter = converters.firstOrNull { it.accepts(source) }
                ?: throw ConversionException("Unsupported file type: ${source.displayName}")
            val prefix = if (sources.size > 1) "[${i + 1}/${sources.size}] " else ""
            val result = converter.convert(context, source, options) { done, total, msg ->
                progress.onProgress(i * 1000 + done * 1000 / maxOf(total, 1), sources.size * 1000, prefix + msg)
            }
            if (sources.size == 1) title = result.title
            parts += if (sources.size > 1) "<!-- Source: ${source.displayName.replace("--", "- -")} -->\n\n${result.markdown.trim()}" else result.markdown.trim()
        }
        DocumentConverterResult(parts.joinToString("\n\n") + "\n", title)
    }
}
