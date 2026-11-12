package com.ply.editor.model

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.util.concurrent.atomic.AtomicLong

object LayerIds {
    private val next = AtomicLong(1L)
    fun next(): Long = next.getAndIncrement()

    /** The counter is per-process; loaded projects carry ids from an earlier one.
     *  Call this with the highest persisted id so new layers can never collide —
     *  a duplicate id crashes the layers list and corrupts history lookups. */
    fun ensureAbove(id: Long) {
        next.updateAndGet { current -> if (id >= current) id + 1 else current }
    }
}

enum class LayerKind { RASTER, TEXT, SHAPE }

enum class BlendMode(val label: String, val native: android.graphics.BlendMode) {
    NORMAL("Normal", android.graphics.BlendMode.SRC_OVER),
    MULTIPLY("Multiply", android.graphics.BlendMode.MULTIPLY),
    SCREEN("Screen", android.graphics.BlendMode.SCREEN),
    OVERLAY("Overlay", android.graphics.BlendMode.OVERLAY),
    DARKEN("Darken", android.graphics.BlendMode.DARKEN),
    LIGHTEN("Lighten", android.graphics.BlendMode.LIGHTEN),
    DIFFERENCE("Difference", android.graphics.BlendMode.DIFFERENCE),
    ADD("Add", android.graphics.BlendMode.PLUS)
}

enum class TextAlignSpec { LEFT, CENTER, RIGHT }

data class TextSpec(
    val text: String,
    val sizePx: Float,
    val color: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlignSpec = TextAlignSpec.CENTER
)

/** A single layer. Bitmaps are treated as immutable once committed: any edit replaces
 *  the bitmap so history snapshots can keep sharing untouched layers cheaply. */
data class Layer(
    val id: Long = LayerIds.next(),
    val name: String,
    val bitmap: Bitmap,
    val kind: LayerKind = LayerKind.RASTER,
    val cx: Float,
    val cy: Float,
    val scale: Float = 1f,
    /** Extra per-axis multipliers used by the resizer grips; corners keep them equal. */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val visible: Boolean = true,
    val blend: BlendMode = BlendMode.NORMAL,
    val text: TextSpec? = null
) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    fun matrix(): Matrix = Matrix().apply {
        postTranslate(-width / 2f, -height / 2f)
        postScale(scale * scaleX, scale * scaleY)
        postRotate(rotation)
        postTranslate(cx, cy)
    }

    /** Map a document-space point into this layer's bitmap coordinates. */
    fun toLayerSpace(point: Offset): Offset {
        val m = matrix()
        val inverse = Matrix()
        if (!m.invert(inverse)) return point
        val pts = floatArrayOf(point.x, point.y)
        inverse.mapPoints(pts)
        return Offset(pts[0], pts[1])
    }

    fun boundsInDocument(): Rect {
        val pts = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )
        matrix().mapPoints(pts)
        var minX = pts[0]; var maxX = pts[0]
        var minY = pts[1]; var maxY = pts[1]
        var i = 2
        while (i < pts.size) {
            minX = minOf(minX, pts[i]); maxX = maxOf(maxX, pts[i])
            minY = minOf(minY, pts[i + 1]); maxY = maxOf(maxY, pts[i + 1])
            i += 2
        }
        return Rect(minX, minY, maxX, maxY)
    }

    fun isInsideDocument(doc: Document): Boolean {
        val b = boundsInDocument()
        return b.right > 0f && b.bottom > 0f && b.left < doc.width && b.top < doc.height
    }
}

data class Document(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    /** Dimensions the document was created with; content scaling references these. */
    val originalWidth: Int = width,
    val originalHeight: Int = height,
    val background: Int,
    val layers: List<Layer>,
    /** Null when nothing is selected, for example after tapping outside the canvas. */
    val activeId: Long?
) {
    val active: Layer? get() = activeId?.let { id -> layers.firstOrNull { it.id == id } }

    fun indexOf(layerId: Long): Int = layers.indexOfFirst { it.id == layerId }

    fun replaceLayer(layer: Layer): Document {
        val idx = indexOf(layer.id)
        if (idx < 0) return this
        val copy = layers.toMutableList()
        copy[idx] = layer
        return copy(layers = copy)
    }

    fun addLayer(layer: Layer, at: Int = layers.size): Document {
        val copy = layers.toMutableList()
        copy.add(at.coerceIn(0, copy.size), layer)
        return copy(layers = copy, activeId = layer.id)
    }

    fun removeLayer(layerId: Long): Document {
        val idx = indexOf(layerId)
        if (idx < 0 || layers.size <= 1) return this
        val copy = layers.toMutableList()
        copy.removeAt(idx)
        val nextActive = copy.getOrNull((idx - 1).coerceAtLeast(0))?.id ?: copy.last().id
        return copy(layers = copy, activeId = if (activeId == layerId) nextActive else activeId)
    }

    /** activeId is the top-most selected layer; index 0 is the bottom of the stack. */
    fun moveLayer(layerId: Long, delta: Int): Document {
        val idx = indexOf(layerId)
        val target = idx + delta
        if (idx < 0 || target < 0 || target >= layers.size) return this
        val copy = layers.toMutableList()
        val item = copy.removeAt(idx)
        copy.add(target, item)
        return copy(layers = copy)
    }

    fun cropped(rect: Rect): Document {
        val left = rect.left.toInt()
        val top = rect.top.toInt()
        val w = rect.width.toInt().coerceAtLeast(1)
        val h = rect.height.toInt().coerceAtLeast(1)
        val moved = layers.map { it.copy(cx = it.cx - left, cy = it.cy - top) }
        return copy(width = w, height = h, layers = moved)
    }

    /** Change canvas dimensions without scaling artwork; existing artwork keeps its position. */
    fun resizedCanvas(width: Int, height: Int): Document = copy(width = width, height = height)

    /** Scale the whole composition, including layer transforms. */
    fun scaledContent(factor: Float): Document = copy(
        width = (width * factor).toInt().coerceAtLeast(1),
        height = (height * factor).toInt().coerceAtLeast(1),
        layers = layers.map { it.copy(cx = it.cx * factor, cy = it.cy * factor, scale = it.scale * factor) }
    )

    /** Rescale layer positions/sizes without changing canvas dimensions. */
    fun rescaleContent(factor: Float): Document = copy(
        layers = layers.map { it.copy(cx = it.cx * factor, cy = it.cy * factor, scale = it.scale * factor) }
    )

    companion object {
        fun blank(
            name: String = "Untitled",
            width: Int = 2048,
            height: Int = 2048,
            background: Int = 0xFFFFFFFF.toInt()
        ): Document {
            val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val layer = Layer(name = "Background", bitmap = base, cx = width / 2f, cy = height / 2f)
            return Document(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                width = width,
                height = height,
                background = background,
                layers = listOf(layer),
                activeId = layer.id
            )
        }
    }
}
