package com.markitdown.local.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.markitdown.local.ocr.OcrEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.min

/**
 * PDF -> Markdown. Mirrors markitdown's PdfConverter (pdfminer text extraction) using PDFBox,
 * and adds what the Python version gets from a cloud service: pages without a usable text
 * layer (scans, photos) are rendered and OCR'd locally with Tesseract.
 */
class PdfConverter(private val ocr: OcrEngine) : DocumentConverter {

    override fun accepts(source: SourceDocument): Boolean =
        source.mimeType in setOf("application/pdf", "application/x-pdf") || source.extension == "pdf"

    override suspend fun convert(
        context: Context,
        source: SourceDocument,
        options: ConversionOptions,
        progress: ProgressListener,
    ): DocumentConverterResult {
        PDFBoxResourceLoader.init(context.applicationContext)
        val work = File(context.cacheDir, "work").apply { mkdirs() }
        val file = File.createTempFile("input", ".pdf", work)
        try {
            context.contentResolver.openInputStream(source.uri).use { input ->
                requireNotNull(input) { "Cannot open ${source.displayName}" }
                file.outputStream().use { input.copyTo(it) }
            }
            val document = try {
                PDDocument.load(file, MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
            } catch (e: InvalidPasswordException) {
                throw ConversionException("${source.displayName} is password-protected.", e)
            } catch (e: Exception) {
                throw ConversionException("${source.displayName} is not a readable PDF.", e)
            }
            document.use { doc ->
                val title = doc.documentInformation?.title?.takeIf { it.isNotBlank() }
                val pages = extractPages(doc, file, options, progress)
                return DocumentConverterResult(MarkdownFormatter.format(pages, options.pageMarkers), title)
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun extractPages(
        doc: PDDocument,
        file: File,
        options: ConversionOptions,
        progress: ProgressListener,
    ): List<PageLines> {
        val count = doc.numberOfPages
        val stripper = PdfLineStripper()
        val result = ArrayList<PageLines>(count)
        var renderer: PageRasterizer? = null
        try {
            for (page in 1..count) {
                currentCoroutineContext().ensureActive()
                progress.onProgress(page - 1, count, "Reading page $page of $count")
                val textLines = if (options.forceOcr) emptyList() else stripper.extract(doc, page)
                if (!options.forceOcr && hasUsableText(textLines)) {
                    result += PageLines(textLines, ocr = false)
                    continue
                }
                progress.onProgress(page - 1, count, "OCR page $page of $count")
                val r = renderer ?: PageRasterizer(file, doc).also { renderer = it }
                val bitmap = r.render(page - 1)
                try {
                    result += PageLines(ocr.recognize(bitmap, options.ocrLanguages), ocr = true)
                } finally {
                    bitmap.recycle()
                }
            }
            progress.onProgress(count, count, "Done")
        } finally {
            renderer?.close()
        }
        return result
    }

    /** A page needs OCR if it has (almost) no text, or the text layer is mostly undecodable glyphs. */
    private fun hasUsableText(lines: List<TextLine>): Boolean {
        var total = 0
        var bad = 0
        for (l in lines) for (c in l.text) {
            if (c.isWhitespace()) continue
            total++
            if (c == '�' || c in ''..'' || Character.isISOControl(c)) bad++
        }
        return total >= 15 && bad < total * 0.3
    }

    /** Renders pages at ~300 DPI with Android's PdfRenderer, falling back to PDFBox's renderer. */
    private class PageRasterizer(file: File, private val doc: PDDocument) : AutoCloseable {
        private val pfd: ParcelFileDescriptor? =
            runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        private val platform: PdfRenderer? = pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
        private val fallback by lazy { PDFRenderer(doc) }

        fun render(index: Int): Bitmap {
            platform?.let { r ->
                r.openPage(index).use { page ->
                    val scale = scaleFor(page.width.toFloat(), page.height.toFloat())
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bmp.eraseColor(Color.WHITE) // PdfRenderer leaves the background transparent
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return bmp
                }
            }
            val box = doc.getPage(index).cropBox
            val scale = scaleFor(box.width, box.height)
            return fallback.renderImage(index, scale, ImageType.RGB)
        }

        private fun scaleFor(widthPt: Float, heightPt: Float): Float {
            val target = 300f / 72f
            val maxSide = 3500f
            return min(target, maxSide / maxOf(widthPt, heightPt, 1f))
        }

        override fun close() {
            runCatching { platform?.close() }
            runCatching { pfd?.close() }
        }
    }
}
