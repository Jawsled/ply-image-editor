package com.ply.editor.io

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlEffort
import com.ply.editor.engine.Compositor
import com.ply.editor.model.Document
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** @param needsBackground JPEG and HEIC have no alpha channel; the flattening
 *  render must composite onto white first. */
enum class ExportFormat(
    val label: String,
    val mime: String,
    val extension: String,
    val needsBackground: Boolean
) {
    PNG("PNG", "image/png", "png", false),
    JPEG("JPEG", "image/jpeg", "jpg", true),
    WEBP("WebP", "image/webp", "webp", false),
    JXL("JPEG XL", "image/jxl", "jxl", false),
    HEIC("HEIC", "image/heic", "heic", true);

    /** Whether the quality slider applies to this format. */
    val supportsQuality: Boolean
        get() = this != PNG
}

data class ExportResult(val uri: Uri, val displayName: String)

object Exporter {

    fun export(
        context: Context,
        doc: Document,
        format: ExportFormat,
        quality: Int,
        baseName: String
    ): ExportResult? {
        val bitmap = if (format.needsBackground) {
            // No-alpha formats: composite onto white so transparent layers do
            // not turn black.
            val flat = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(0xFFFFFFFF.toInt())
            Compositor.drawDocument(canvas, doc, paintBackground = true)
            flat
        } else {
            Compositor.renderToBitmap(doc)
        }

        val displayName = buildName(baseName, format)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ply")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        return try {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                when (format) {
                    ExportFormat.PNG ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, quality.coerceIn(1, 100), out)
                    ExportFormat.JPEG ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
                    ExportFormat.WEBP -> bitmap.compress(
                        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP,
                        quality.coerceIn(1, 100),
                        out
                    )
                    ExportFormat.JXL -> {
                        val bytes = encodeJxl(bitmap, quality.coerceIn(1, 100))
                        out.write(bytes)
                    }
                    ExportFormat.HEIC -> writeHeic(bitmap, quality.coerceIn(1, 100), out)
                }
            } ?: return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            bitmap.recycle()
            ExportResult(uri, displayName)
        } catch (_: Throwable) {
            bitmap.recycle()
            null
        }
    }

    private fun encodeJxl(bitmap: Bitmap, quality: Int): ByteArray {
        // jxl-coder 2.6.0: quality 0 means "use distance"; a 1..100 JPEG-like
        // quality with SQUIRREL effort keeps exports quick on phones.
        return JxlCoder.encode(
            bitmap,
            JxlChannelsConfiguration.RGB,
            JxlCompressionOption.LOSSY,
            JxlEffort.SQUIRREL,
            quality.coerceIn(1, 100)
        )
    }

    /**
     * HEIC has no public Bitmap.compress path; the Jetpack HEIF writer takes
     * bitmaps directly (input mode BITMAP) with a JPEG-like quality.
     */
    private fun writeHeic(bitmap: Bitmap, quality: Int, out: java.io.OutputStream) {
        // The OutputStream builder overload is not public; stage to a temp file.
        val tmp = java.io.File.createTempFile("ply_export", ".heic")
        try {
            val writer = androidx.heifwriter.HeifWriter.Builder(
                tmp.absolutePath,
                bitmap.width,
                bitmap.height,
                androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP
            )
                .setQuality(quality.coerceIn(1, 100))
                .build()
            try {
                writer.start()
                writer.addBitmap(bitmap)
                // stop(timeoutMs) finalizes the container and releases resources;
                // close() is restricted to the library itself.
                writer.stop(5_000L)
            } finally {
                tmp.inputStream().use { input -> input.copyTo(out) }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun buildName(baseName: String, format: ExportFormat): String {
        val clean = baseName.trim().ifBlank { "ply" }
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(' ', '_')
            .take(40)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${clean}_$stamp.${format.extension}"
    }
}
