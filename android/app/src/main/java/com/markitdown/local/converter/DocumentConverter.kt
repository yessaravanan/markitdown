package com.markitdown.local.converter

import android.content.Context
import android.net.Uri

/**
 * Kotlin/Android counterpart of markitdown's `DocumentConverter`
 * (packages/markitdown/src/markitdown/_base_converter.py). Each converter decides whether it
 * accepts a source and turns it into Markdown. Everything runs on-device.
 */
interface DocumentConverter {
    fun accepts(source: SourceDocument): Boolean

    suspend fun convert(
        context: Context,
        source: SourceDocument,
        options: ConversionOptions,
        progress: ProgressListener,
    ): DocumentConverterResult
}

data class SourceDocument(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
) {
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase()
}

data class ConversionOptions(
    /** Tesseract language string, e.g. "eng" or "eng+tam". */
    val ocrLanguages: String = "eng",
    /** OCR every PDF page even when it has a text layer (for PDFs with a broken text layer). */
    val forceOcr: Boolean = false,
    /** Emit image metadata (size, date, camera, GPS) like markitdown's ImageConverter. */
    val includeMetadata: Boolean = true,
    /** Emit `<!-- Page N -->` comments between PDF pages. */
    val pageMarkers: Boolean = true,
)

data class DocumentConverterResult(
    val markdown: String,
    val title: String? = null,
)

fun interface ProgressListener {
    /** [done] of [total] units finished; [message] is shown to the user. */
    fun onProgress(done: Int, total: Int, message: String)
}

class ConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)
