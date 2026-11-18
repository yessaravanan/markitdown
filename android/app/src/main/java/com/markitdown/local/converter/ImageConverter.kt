package com.markitdown.local.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.markitdown.local.ocr.OcrEngine
import kotlin.math.max

/**
 * Image -> Markdown. Like markitdown's ImageConverter it emits the image metadata
 * (ImageSize, DateTimeOriginal, GPSPosition, ...), but instead of sending the picture to a
 * cloud LLM it extracts the text with on-device Tesseract OCR.
 */
class ImageConverter(private val ocr: OcrEngine) : DocumentConverter {

    override fun accepts(source: SourceDocument): Boolean =
        source.mimeType?.startsWith("image/") == true ||
            source.extension in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "tif", "tiff")

    override suspend fun convert(
        context: Context,
        source: SourceDocument,
        options: ConversionOptions,
        progress: ProgressListener,
    ): DocumentConverterResult {
        val resolver = context.contentResolver
        val exif = runCatching { resolver.openInputStream(source.uri)?.use { ExifInterface(it) } }.getOrNull()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ConversionException("${source.displayName} is not a supported image.")
        }

        val md = StringBuilder()
        if (options.includeMetadata) appendMetadata(md, exif, bounds.outWidth, bounds.outHeight)

        progress.onProgress(0, 1, "OCR ${source.displayName}")
        val bitmap = decode(context, source, bounds, exif)
        val lines = try {
            ocr.recognize(bitmap, options.ocrLanguages)
        } finally {
            bitmap.recycle()
        }
        val text = MarkdownFormatter.format(listOf(PageLines(lines, ocr = true)), pageMarkers = false).trim()
        if (md.isNotEmpty()) md.append('\n')
        md.append(text.ifEmpty { "*No text found in image.*" }).append('\n')
        progress.onProgress(1, 1, "Done")
        return DocumentConverterResult(md.toString(), null)
    }

    private fun decode(
        context: Context,
        source: SourceDocument,
        bounds: BitmapFactory.Options,
        exif: ExifInterface?,
    ): Bitmap {
        // Keep the longest side <= 4000 px: plenty for OCR, bounded memory.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw ConversionException("Could not decode ${source.displayName}.")

        // Camera photos are often stored sideways with an EXIF rotation flag; OCR needs them upright.
        val matrix = Matrix()
        when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return decoded.ensureOpaque()
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated.ensureOpaque()
    }

    /** Transparent PNGs (e.g. screenshots of dark text on alpha) OCR badly; flatten onto white. */
    private fun Bitmap.ensureOpaque(): Bitmap {
        if (!hasAlpha()) return this
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.eraseColor(android.graphics.Color.WHITE)
        android.graphics.Canvas(out).drawBitmap(this, 0f, 0f, null)
        recycle()
        return out
    }

    private fun appendMetadata(md: StringBuilder, exif: ExifInterface?, width: Int, height: Int) {
        val rotated = exif?.rotationDegrees == 90 || exif?.rotationDegrees == 270
        md.append("ImageSize: ").append(if (rotated) "${height}x$width" else "${width}x$height").append('\n')
        if (exif == null) return
        fun add(label: String, tag: String) {
            exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { md.append(label).append(": ").append(it).append('\n') }
        }
        add("Description", ExifInterface.TAG_IMAGE_DESCRIPTION)
        add("Artist", ExifInterface.TAG_ARTIST)
        add("DateTimeOriginal", ExifInterface.TAG_DATETIME_ORIGINAL)
        add("CreateDate", ExifInterface.TAG_DATETIME_DIGITIZED)
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        if (make.isNotEmpty() || model.isNotEmpty()) md.append("Camera: ").append("$make $model".trim()).append('\n')
        exif.latLong?.let { (lat, lon) -> md.append("GPSPosition: %.6f, %.6f\n".format(java.util.Locale.US, lat, lon)) }
    }
}
