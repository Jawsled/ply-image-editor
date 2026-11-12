package com.ply.editor.model

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/**
 * One of the eight transform grips drawn around the active layer.
 *
 * [ux] and [uy] are the grip's position in layer-normalized coordinates: -1 is the
 * near edge, 0 the midpoint and 1 the far edge. Corner grips have both set, edge
 * grips have exactly one.
 */
enum class ResizeHandle(val ux: Int, val uy: Int) {
    TOP_LEFT(-1, -1),
    TOP(0, -1),
    TOP_RIGHT(1, -1),
    RIGHT(1, 0),
    BOTTOM_RIGHT(1, 1),
    BOTTOM(0, 1),
    BOTTOM_LEFT(-1, 1),
    LEFT(-1, 0);

    val isCorner: Boolean get() = ux != 0 && uy != 0
}

private const val MIN_SCALE = 0.02f
private const val MAX_SCALE = 24f

/** Document-space position of one grip, following the layer's scale and rotation. */
fun Layer.handlePosition(handle: ResizeHandle): Offset =
    mapPoint((handle.ux + 1) * width / 2f, (handle.uy + 1) * height / 2f)

/** All eight grip positions in document space. */
fun Layer.handlePositions(): Map<ResizeHandle, Offset> =
    ResizeHandle.entries.associateWith(::handlePosition)

/**
 * Nearest grip to [point], or null when none is within [tolerance] document units.
 * Corners win over edge midpoints so they stay grabbable on small layers.
 */
fun Layer.handleAt(point: Offset, tolerance: Float): ResizeHandle? {
    var best: ResizeHandle? = null
    var bestScore = Float.MAX_VALUE
    for (handle in ResizeHandle.entries) {
        val distance = (handlePosition(handle) - point).getDistance()
        if (distance > tolerance) continue
        val score = distance - if (handle.isCorner) tolerance * 0.4f else 0f
        if (score < bestScore) {
            bestScore = score
            best = handle
        }
    }
    return best
}

private fun Layer.mapPoint(x: Float, y: Float): Offset {
    val pts = floatArrayOf(x, y)
    matrix().mapPoints(pts)
    return Offset(pts[0], pts[1])
}

/**
 * Drag [handle] to [target] (document space) and return the resized layer.
 *
 * The grip opposite the one being dragged stays anchored, so the artwork grows
 * away from that side. Corner grips scale both axes by the same factor, which
 * keeps the layer's current aspect ratio; edge grips change one axis only.
 */
fun Layer.resizedWith(handle: ResizeHandle, target: Offset): Layer {
    if (width <= 0 || height <= 0) return this

    val radians = Math.toRadians(rotation.toDouble())
    val cosR = cos(radians).toFloat()
    val sinR = sin(radians).toFloat()
    // Layer axes in document space.
    val ux = cosR
    val uy = sinR
    val vx = -sinR
    val vy = cosR

    val hx = handle.ux.toFloat()
    val hy = handle.uy.toFloat()

    val widthNow = width * scale * scaleX
    val heightNow = height * scale * scaleY
    if (widthNow <= 0f || heightNow <= 0f) return this

    // The fixed point: the grip opposite the one being dragged.
    val anchorX = cx - ux * widthNow / 2f * hx - vx * heightNow / 2f * hy
    val anchorY = cy - uy * widthNow / 2f * hx - vy * heightNow / 2f * hy

    val dx = target.x - anchorX
    val dy = target.y - anchorY
    val alongU = (dx * ux + dy * uy) * hx
    val alongV = (dx * vx + dy * vy) * hy

    var factorX = 1f
    var factorY = 1f
    if (hx != 0f && hy != 0f) {
        // Least-squares fit of the diagonal: keeps the current aspect ratio while
        // tracking the finger, whichever direction it is dragged.
        val k = (alongU * widthNow + alongV * heightNow) /
            (widthNow * widthNow + heightNow * heightNow)
        factorX = k
        factorY = k
    } else if (hx != 0f) {
        factorX = alongU / widthNow
    } else {
        factorY = alongV / heightNow
    }

    val scaleNowX = (scale * scaleX).coerceAtLeast(1e-4f)
    val scaleNowY = (scale * scaleY).coerceAtLeast(1e-4f)
    val newScaleX = (scaleNowX * factorX).coerceIn(MIN_SCALE, MAX_SCALE)
    val newScaleY = (scaleNowY * factorY).coerceIn(MIN_SCALE, MAX_SCALE)

    val newWidth = width * newScaleX
    val newHeight = height * newScaleY
    val newCx = anchorX + ux * newWidth / 2f * hx + vx * newHeight / 2f * hy
    val newCy = anchorY + uy * newWidth / 2f * hx + vy * newHeight / 2f * hy

    return copy(
        cx = newCx,
        cy = newCy,
        scaleX = newScaleX / scale,
        scaleY = newScaleY / scale
    )
}
