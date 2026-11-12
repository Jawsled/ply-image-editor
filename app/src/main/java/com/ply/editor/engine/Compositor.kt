package com.ply.editor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import com.ply.editor.model.Document
import com.ply.editor.model.Layer
import kotlin.math.roundToInt

object Compositor {

    fun drawDocument(canvas: Canvas, doc: Document, paintBackground: Boolean = true) {
        if (paintBackground) {
            val bg = doc.background
            if (android.graphics.Color.alpha(bg) > 0) {
                canvas.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), Paint().apply { color = bg })
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        doc.layers.forEach { layer ->
            if (!layer.visible || layer.alpha <= 0.001f) return@forEach
            paint.reset()
            paint.isAntiAlias = true
            paint.isFilterBitmap = true
            paint.alpha = (layer.alpha.coerceIn(0f, 1f) * 255f).roundToInt()
            if (layer.blend != com.ply.editor.model.BlendMode.NORMAL) {
                paint.blendMode = layer.blend.native
            }
            canvas.save()
            canvas.concat(layer.matrix())
            canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
            canvas.restore()
        }
    }

    fun renderLayers(layers: List<Layer>, width: Int, height: Int, background: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (android.graphics.Color.alpha(background) > 0) {
            canvas.drawColor(background)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        layers.forEach { layer ->
            if (!layer.visible || layer.alpha <= 0.001f) return@forEach
            paint.reset()
            paint.isAntiAlias = true
            paint.isFilterBitmap = true
            paint.alpha = (layer.alpha.coerceIn(0f, 1f) * 255f).roundToInt()
            if (layer.blend != com.ply.editor.model.BlendMode.NORMAL) {
                paint.blendMode = layer.blend.native
            }
            canvas.save()
            canvas.concat(layer.matrix())
            canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
            canvas.restore()
        }
        return out
    }

    fun renderToBitmap(doc: Document, scale: Float = 1f): Bitmap {
        val w = (doc.width * scale).roundToInt().coerceAtLeast(1)
        val h = (doc.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.scale(scale, scale)
        drawDocument(canvas, doc)
        return out
    }

    /** Small preview of the flattened document, useful for project cards. */
    fun thumbnail(doc: Document, maxSize: Int = 512): Bitmap {
        val factor = minOf(maxSize.toFloat() / doc.width, maxSize.toFloat() / doc.height, 1f)
        return renderToBitmap(doc, factor)
    }

    /** Merge an existing stack span into one flat raster layer. */
    fun flattenRange(doc: Document, layers: List<Layer>): Layer {
        val merged = renderLayers(layers, doc.width, doc.height, 0x00000000)
        return Layer(
            name = "Merged",
            bitmap = merged,
            cx = doc.width / 2f,
            cy = doc.height / 2f
        )
    }

    fun flatten(doc: Document): Layer = flattenRange(doc, doc.layers).copy(name = "Flattened")

    fun clear(bitmap: Bitmap): Bitmap {
        val c = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        return c
    }

    fun eraseAll(bitmap: Bitmap): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(copy).drawColor(0, PorterDuff.Mode.CLEAR)
        return copy
    }
}
