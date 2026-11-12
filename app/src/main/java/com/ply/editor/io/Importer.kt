package com.ply.editor.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.awxkee.jxlcoder.JxlCoder
import kotlin.math.max
import kotlin.math.roundToInt

object Importer {

    /** Decode a picked image, honoring EXIF orientation and capping the long edge. */
    fun decode(context: Context, uri: Uri, maxDimension: Int = 3072): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: return null

            // JPEG XL is not a platform format; the jxl-coder library handles it.
            if (JxlCoder.isJXL(bytes)) {
                val decoded = decodeJxl(bytes, maxDimension) ?: return null
                return downscale(decoded, maxDimension)
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return null
            val orientation = readOrientation(bytes)
            val oriented = applyOrientation(decoded, orientation)
            downscale(oriented, maxDimension)
        } catch (_: Throwable) {
            null
        }
    }

    /** EXIF orientation from raw file bytes; NORMAL when absent or unreadable. */
    private fun readOrientation(bytes: ByteArray): Int = try {
        java.io.ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * JPEG XL has no platform decoder; the jxl-coder library handles both the
     * codestream and container forms, with sampling for the size cap.
     */
    private fun decodeJxl(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val decoded = try {
            JxlCoder.decodeSampled(bytes, maxDimension, maxDimension)
                ?: JxlCoder.decode(bytes)
        } catch (_: Throwable) {
            null
        } ?: return null
        // The library does not apply EXIF orientation; photographs exported by
        // phones carry one, so honor it like every other format.
        return applyOrientation(decoded, readOrientation(bytes))
    }

    /**
     * The sample-size pass only halves dimensions, so a 3400×4400 photo would otherwise
     * stay at full resolution. Scale the long edge to [maxDimension] afterwards.
     */
    private fun downscale(source: Bitmap, maxDimension: Int): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= maxDimension) return source
        val factor = maxDimension.toFloat() / longEdge
        val width = (source.width * factor).roundToInt().coerceAtLeast(1)
        val height = (source.height * factor).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        if (matrix.isIdentity) return source
        return try {
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (rotated !== source) source.recycle()
            rotated
        } catch (_: Throwable) {
            source
        }
    }
}
