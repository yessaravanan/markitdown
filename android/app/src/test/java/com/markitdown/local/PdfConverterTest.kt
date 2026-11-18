package com.markitdown.local

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.markitdown.local.converter.ConversionOptions
import com.markitdown.local.converter.PdfConverter
import com.markitdown.local.converter.SourceDocument
import com.markitdown.local.ocr.OcrEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfConverterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** PDFs from the upstream markitdown test-suite in this fork. */
    private val upstreamFiles = File("../../packages/markitdown/tests/test_files")

    private fun convert(file: File, options: ConversionOptions = ConversionOptions(pageMarkers = false)): String =
        runBlocking {
            PdfConverter(OcrEngine(context)).convert(
                context,
                SourceDocument(Uri.fromFile(file), file.name, "application/pdf"),
                options,
            ) { _, _, _ -> }.markdown
        }

    @Test
    fun upstreamTestPdf() {
        val md = convert(File(upstreamFiles, "test.pdf"))
        // Same assertion as markitdown's own test vector for test.pdf.
        assertTrue(md.contains("While there is contemporaneous exploration of multi-agent approaches"))
        println(md.take(1500))
    }

    @Test
    fun upstreamMasterFormatPdf() {
        val md = convert(File(upstreamFiles, "masterformat_partial_numbering.pdf"))
        val isolated = md.lines().map { it.trim() }.filter { it.matches(Regex("^\\.\\d+$")) }
        assertTrue("isolated numbering: $isolated\n$md", isolated.isEmpty())
        println(md.take(1500))
    }

    @Test
    fun generatedPdfKeepsStructure() {
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "generated.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                var y = 720f
                fun line(text: String, font: PDFont, size: Float, gap: Float) {
                    cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(72f, y); cs.showText(text); cs.endText()
                    y -= gap
                }
                line("Field Survey Results", PDType1Font.HELVETICA_BOLD, 22f, 34f)
                line("Soil samples", PDType1Font.HELVETICA_BOLD, 15f, 24f)
                line("Samples were collected from twelve plots along the northern ridge in", PDType1Font.HELVETICA, 11f, 14f)
                line("late March, after the thaw, and stored at four degrees until analysis.", PDType1Font.HELVETICA, 11f, 24f)
                line("- Plot A: loam", PDType1Font.HELVETICA, 11f, 14f)
                line("- Plot B: clay", PDType1Font.HELVETICA, 11f, 14f)
                line("7", PDType1Font.HELVETICA, 9f, 14f)
            }
            doc.save(file)
        }
        val md = convert(file)
        println(md)
        assertTrue(md, md.contains("# Field Survey Results"))
        assertTrue(md, md.contains("## Soil samples"))
        assertTrue(md, md.contains("ridge in late March"))
        assertTrue(md, md.contains("- Plot A: loam\n- Plot B: clay"))
        assertFalse("page number should be dropped:\n$md", md.lines().any { it.trim() == "7" })
    }
}
