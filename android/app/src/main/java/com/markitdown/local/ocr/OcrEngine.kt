package com.markitdown.local.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.markitdown.local.converter.ConversionException
import com.markitdown.local.converter.TextLine
import java.io.File
import java.io.InputStream

/**
 * On-device OCR with Tesseract 5. Language models live in the app's private storage:
 * bundled ones are copied from the APK assets, extra ones can be imported by the user
 * from a local .traineddata file. Nothing is ever downloaded.
 *
 * Not thread-safe; use one instance per conversion.
 */
class OcrEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var api: TessBaseAPI? = null
    private var loadedLanguages: String? = null

    fun recognize(bitmap: Bitmap, languages: String): List<TextLine> {
        val tess = ensureApi(languages)
        tess.setImage(bitmap)
        tess.getUTF8Text() // runs recognition; results are read via the iterator below
        val lines = mutableListOf<TextLine>()
        val it = tess.resultIterator ?: return lines.also { tess.clear() }
        try {
            it.begin()
            do {
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
                if (text.isEmpty()) continue
                val confidence = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                // Very low confidence lines are usually texture / photo noise, not text.
                if (confidence < 30f && text.count { c -> c.isLetterOrDigit() } < 6) continue
                if (confidence < 15f) continue
                val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                val paragraphStart = it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA) ||
                    it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)
                lines += TextLine(text, rect.height().toFloat(), bold = false, paragraphStart = paragraphStart)
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
        } finally {
            it.delete()
            tess.clear()
        }
        return lines
    }

    private fun ensureApi(languages: String): TessBaseAPI {
        val wanted = languages.ifBlank { DEFAULT_LANGUAGE }
        api?.let { if (loadedLanguages == wanted) return it }
        api?.recycle()
        api = null
        installBundledModels(appContext)
        val missing = wanted.split('+').filter { !File(tessdataDir(appContext), "$it.traineddata").exists() }
        if (missing.isNotEmpty()) throw ConversionException("OCR language not installed: ${missing.joinToString()}")
        val tess = TessBaseAPI()
        if (!tess.init(dataRoot(appContext).absolutePath, wanted, TessBaseAPI.OEM_LSTM_ONLY)) {
            tess.recycle()
            throw ConversionException("Could not start OCR for languages: $wanted")
        }
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api = tess
        loadedLanguages = wanted
        return tess
    }

    override fun close() {
        api?.recycle()
        api = null
    }

    companion object {
        const val DEFAULT_LANGUAGE = "eng"
        private const val ASSET_DIR = "tessdata"

        fun dataRoot(context: Context) = File(context.filesDir, "tesseract")
        fun tessdataDir(context: Context) = File(dataRoot(context), "tessdata").apply { mkdirs() }

        /** Copies models shipped inside the APK into private storage (Tesseract needs real files). */
        @Synchronized
        fun installBundledModels(context: Context) {
            val assets = context.assets
            val names = assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".traineddata") }
            val dir = tessdataDir(context)
            for (name in names) {
                val target = File(dir, name)
                val size = runCatching { assets.openFd("$ASSET_DIR/$name").use { it.length } }.getOrDefault(-1L)
                if (target.exists() && (size < 0 || target.length() == size)) continue
                assets.open("$ASSET_DIR/$name").use { copyAtomically(it, target) }
            }
        }

        fun installedLanguages(context: Context): List<String> {
            installBundledModels(context)
            return tessdataDir(context).listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".traineddata") && it.length() > 0 }
                .map { it.name.removeSuffix(".traineddata") }
                .sorted()
        }

        /** Installs a user-provided model, e.g. tam.traineddata copied to the phone over USB. */
        fun importModel(context: Context, fileName: String, input: InputStream): String {
            val lang = fileName.substringAfterLast('/').removeSuffix(".traineddata")
            require(fileName.endsWith(".traineddata") && lang.matches(Regex("[A-Za-z0-9_]{2,40}"))) {
                "Pick a Tesseract model file named like 'tam.traineddata'."
            }
            copyAtomically(input, File(tessdataDir(context), "$lang.traineddata"))
            return lang
        }

        fun deleteModel(context: Context, lang: String) {
            File(tessdataDir(context), "$lang.traineddata").delete()
        }

        private fun copyAtomically(input: InputStream, target: File) {
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.outputStream().use { input.copyTo(it) }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw ConversionException("Could not install ${target.name}")
            }
        }
    }
}
