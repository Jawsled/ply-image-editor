package com.ply.editor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Offset
import com.ply.editor.model.Document
import com.ply.editor.model.ShapeKind
import com.ply.editor.model.TextAlignSpec
import com.ply.editor.model.TextSpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

class BrushSession(
    val bitmap: Bitmap,
    color: Int,
    widthPx: Float,
    opacity: Float,
    erase: Boolean
) {
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = widthPx.coerceAtLeast(1f)
        if (erase) {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            alpha = 255
        } else {
            this.color = color
            alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
        }
    }
    private var last: Offset? = null

    fun begin(point: Offset) {
        last = point
        canvas.drawPoint(point.x, point.y, paint)
    }

    fun extend(point: Offset) {
        val previous = last
        if (previous == null) {
            begin(point)
            return
        }
        canvas.drawLine(previous.x, previous.y, point.x, point.y, paint)
        last = point
    }
}

object Painter {

    fun colorAt(bitmap: Bitmap, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) return 0
        return bitmap.getPixel(x, y)
    }

    fun compositeColorAt(doc: Document, point: Offset): Int {
        val out = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(-point.x, -point.y)
        Compositor.drawDocument(canvas, doc)
        return out.getPixel(0, 0)
    }

    /**
     * Color under [point] for the eyedropper. Falls back to the top-most visible
     * layer when the composite is fully transparent (thin strokes, soft edges), so a
     * tap always returns something useful.
     */
    fun sampleColor(doc: Document, point: Offset): Int {
        val composite = compositeColorAt(doc, point)
        if (android.graphics.Color.alpha(composite) > 0) return composite
        for (layer in doc.layers.asReversed()) {
            if (!layer.visible) continue
            val local = layer.toLayerSpace(point)
            val pixel = colorAt(layer.bitmap, local.x.roundToInt(), local.y.roundToInt())
            if (android.graphics.Color.alpha(pixel) > 0) return pixel
        }
        return composite
    }

    fun drawShape(
        bitmap: Bitmap,
        kind: ShapeKind,
        from: Offset,
        to: Offset,
        fillColor: Int?,
        strokeColor: Int?,
        strokeWidth: Float
    ) {
        val canvas = Canvas(bitmap)
        val rect = RectF(
            minOf(from.x, to.x),
            minOf(from.y, to.y),
            maxOf(from.x, to.x),
            maxOf(from.y, to.y)
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (kind == ShapeKind.LINE) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = strokeWidth.coerceAtLeast(1f)
            paint.color = strokeColor ?: fillColor ?: 0xFF000000.toInt()
            canvas.drawLine(from.x, from.y, to.x, to.y, paint)
            return
        }
        fillColor?.let {
            paint.style = Paint.Style.FILL
            paint.color = it
            if (kind == ShapeKind.RECT) canvas.drawRect(rect, paint) else canvas.drawOval(rect, paint)
        }
        strokeColor?.let {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth.coerceAtLeast(1f)
            paint.color = it
            if (kind == ShapeKind.RECT) canvas.drawRect(rect, paint) else canvas.drawOval(rect, paint)
        }
    }

    /**
     * Longest allowed bitmap edge for rendered text. Beyond it, glyphs would
     * exceed the GPU's maximum texture size and crash the hardware canvas, so
     * longer lines wrap — a case that only triggers on extreme inputs, since
     * 4096 px fits roughly 150+ characters at the default size.
     */
    private const val TEXT_MAX_EDGE = 4096

    /**
     * Render [spec.text] as a bitmap. Text never wraps on its own: each line
     * (split by explicit newlines) gets as much width as it measures. The
     * bitmap edge is capped at [TEXT_MAX_EDGE]; a single line longer than that
     * wraps within the cap instead of crashing the GPU canvas.
     */
    fun renderText(spec: TextSpec, maxEdgePx: Int = TEXT_MAX_EDGE): Bitmap {
        val typeface = Typeface.create(
            Typeface.DEFAULT,
            when {
                spec.bold && spec.italic -> Typeface.BOLD_ITALIC
                spec.bold -> Typeface.BOLD
                spec.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
        )
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spec.sizePx.coerceAtLeast(4f)
            color = spec.color
            this.typeface = typeface
        }
        val padding = (spec.sizePx * 0.12f).coerceAtLeast(4f)
        val lines = spec.text.split("\n")
        val measured = lines.maxOfOrNull { paint.measureText(it) } ?: 1f
        val cap = maxEdgePx.coerceAtLeast(64)
        // Wrap only when a single line would exceed the bitmap cap.
        val contentWidth = min(ceil(measured).toInt(), (cap - padding * 2).toInt().coerceAtLeast(8))
            .coerceAtLeast(8)
        val layout = StaticLayout.Builder
            .obtain(spec.text, 0, spec.text.length, paint, contentWidth)
            .setAlignment(
                when (spec.align) {
                    TextAlignSpec.LEFT -> Layout.Alignment.ALIGN_NORMAL
                    TextAlignSpec.CENTER -> Layout.Alignment.ALIGN_CENTER
                    TextAlignSpec.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                }
            )
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(true)
            .build()
        val width = (contentWidth + padding * 2).toInt().coerceIn(8, cap)
        val height = (layout.height + padding * 2).toInt().coerceIn(8, cap)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(padding, padding)
        layout.draw(canvas)
        return out
    }

    fun adjust(
        bitmap: Bitmap,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cm = ColorMatrix()
        cm.setSaturation(saturation)
        val c = contrast.coerceIn(0.1f, 3f)
        val offset = 128f * (1f - c) + brightness * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, offset,
                0f, c, 0f, 0f, offset,
                0f, 0f, c, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    /** Scanline flood fill. Returns true when at least one pixel changed. */
    fun floodFill(bitmap: Bitmap, startX: Int, startY: Int, newColor: Int, tolerance: Int): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (startX < 0 || startY < 0 || startX >= w || startY >= h) return false
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val target = pixels[startY * w + startX]
        if (target == newColor) return false

        val tol = tolerance.coerceIn(0, 255)
        fun matches(color: Int): Boolean {
            if (color == newColor) return false
            val dr = abs((color shr 16 and 0xFF) - (target shr 16 and 0xFF))
            val dg = abs((color shr 8 and 0xFF) - (target shr 8 and 0xFF))
            val db = abs((color and 0xFF) - (target and 0xFF))
            val da = abs((color ushr 24) - (target ushr 24))
            return dr <= tol && dg <= tol && db <= tol && da <= tol
        }

        var changed = false
        val stack = IntArray(w * h * 2 + 8)
        var sp = 0
        stack[sp++] = startX
        stack[sp++] = startY
        while (sp > 0) {
            val y = stack[--sp]
            val x = stack[--sp]
            val row = y * w
            if (!matches(pixels[row + x])) continue
            var left = x
            while (left > 0 && matches(pixels[row + left - 1])) left--
            var right = x
            while (right < w - 1 && matches(pixels[row + right + 1])) right++

            for (cx in left..right) {
                pixels[row + cx] = newColor
                changed = true
            }

            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny < 0 || ny >= h) continue
                val nrow = ny * w
                var run = false
                for (cx in left..right) {
                    if (matches(pixels[nrow + cx])) {
                        if (!run) {
                            if (sp + 2 <= stack.size) {
                                stack[sp++] = cx
                                stack[sp++] = ny
                            }
                            run = true
                        }
                    } else {
                        run = false
                    }
                }
            }
        }
        if (changed) bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return changed
    }
}
