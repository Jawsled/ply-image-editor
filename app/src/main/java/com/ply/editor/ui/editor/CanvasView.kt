package com.ply.editor.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ply.editor.engine.Compositor
import com.ply.editor.model.Document
import com.ply.editor.model.Layer
import com.ply.editor.model.LayerKind
import com.ply.editor.model.RemoveMode
import com.ply.editor.model.ShapeKind
import com.ply.editor.model.Tool
import com.ply.editor.model.handlePositions
import com.ply.editor.state.EditorViewModel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private object Checker {
    var shader: BitmapShader? = null

    fun paint(): Paint {
        val existing = shader
        if (existing != null) return Paint().apply { shader = existing }
        val cell = 12
        val bitmap = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bitmap)
        c.drawColor(0xFF2C2D33.toInt())
        val light = Paint().apply { color = 0xFF383A42.toInt() }
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), light)
        c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, light)
        val shaderRef = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shader = shaderRef
        return Paint().apply { shader = shaderRef }
    }
}

@Composable
fun EditorCanvas(
    vm: EditorViewModel,
    doc: Document,
    showGrid: Boolean,
    fitKey: Int,
    modifier: Modifier = Modifier
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var viewScale by remember { mutableFloatStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }
    // Screen position of the finger while the Remove brush is painting; drives the loupe.
    var magnifierPoint by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val minSizePx = with(density) { 6.dp.toPx() }
    val cornerGripPx = with(density) { 12.dp.toPx() }
    val edgeGripPx = with(density) { 10.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    LaunchedEffect(doc.id, doc.width, doc.height, viewport, fitKey) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val fit = min(
            viewport.width / doc.width.toFloat(),
            viewport.height / doc.height.toFloat()
        ) * 0.9f
        viewScale = fit.coerceIn(0.01f, 8f)
        viewOffset = Offset(
            (viewport.width - doc.width * viewScale) / 2f,
            (viewport.height - doc.height * viewScale) / 2f
        )
    }

    fun toDoc(point: Offset): Offset = (point - viewOffset) / viewScale

    /** Canvas scale that fits the document with the standard 0.9 margin. */
    fun fitScale(): Float =
        if (viewport.width <= 0 || viewport.height <= 0) {
            1f
        } else {
            computeFit(viewport, doc).coerceIn(0.001f, 8f)
        }

    /** Set the zoom around the viewport center without panning surprises. */
    fun zoomTo(target: Float) {
        if (viewport.width <= 0 || viewport.height <= 0) return
        val clamped = target.coerceIn(0.001f, 8f)
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val docPoint = toDoc(center)
        viewScale = clamped
        viewOffset = center - docPoint * clamped
    }

    Box(
        modifier
            .background(Color(0xFF17181C))
            .onSizeChanged { viewport = it }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(vm.tool, doc.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = 0 // 0 = tool, 1 = transform, 2 = grip resize,
                        // 3 = lasso, 4 = text-chrome transform/rotate, 5 = chrome button
                        var chromeButton: (() -> Unit)? = null
                        var chromeStart = Offset.Zero
                        var chromeStartAngle = 0f
                        var chromeStartScale = 1f
                        var moved = false
                        var last = down.position

                        vm.reportViewScale(viewScale)
                        val gripTolerance = with(density) { 26.dp.toPx() } / viewScale
                        val grip = if (vm.tool == Tool.RESIZE) {
                            vm.activeResizeHandle(toDoc(down.position), gripTolerance)
                        } else {
                            null
                        }
                        val chrome = if (vm.tool == Tool.MOVE || vm.tool == Tool.TEXT) {
                            textChromeHitTest(doc, toDoc(down.position), density, viewScale, viewOffset)
                        } else {
                            null
                        }
                        when (chrome) {
                            is TextChromeHit.Delete, is TextChromeHit.Duplicate -> {
                                // Fire on release unless the finger moves away.
                                chromeButton = {
                                    if (chrome is TextChromeHit.Delete) {
                                        vm.deleteActiveTextLayer()
                                    } else if (chrome is TextChromeHit.Duplicate) {
                                        vm.duplicateActiveTextLayer()
                                    }
                                }
                                mode = 5
                                moved = false
                            }
                            is TextChromeHit.Rotate -> {
                                // Grabbing the handle means rotate+scale; never move.
                                val layer = doc.active
                                if (layer != null) {
                                    vm.selectLayer(layer.id)
                                    vm.beginTextTransform()
                                    chromeStart = down.position
                                    chromeStartAngle = layer.rotation
                                    chromeStartScale = layer.scale
                                    mode = 4
                                    moved = true
                                }
                            }
                            is TextChromeHit.Body -> {
                                vm.selectLayer(chrome.layerId)
                                mode = 6
                                moved = false
                            }
                            null -> when {
                                grip != null -> {
                                    vm.beginResize(grip)
                                    mode = 2
                                    moved = true
                                }
                                vm.tool == Tool.INPAINT && vm.removeMode == RemoveMode.LASSO -> {
                                    vm.beginLasso(toDoc(down.position))
                                    mode = 3
                                }
                                // Tap-to-select: no stroke on down; a clean tap
                                // adds a SAM prompt (handled in the end block).
                                vm.tool == Tool.INPAINT && vm.removeMode == RemoveMode.TAP -> Unit
                                vm.tool != Tool.PAN -> {
                                    vm.strokeStart(toDoc(down.position))
                                    if (vm.tool == Tool.INPAINT || vm.tool == Tool.BRUSH || vm.tool == Tool.ERASER) {
                                        magnifierPoint = down.position
                                    }
                                }
                            }                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break

                            if (pressed >= 2) {
                                if (mode == 0) {
                                    vm.cancelStroke()
                                    mode = 1
                                } else if (mode == 3) {
                                    vm.cancelLasso()
                                    mode = 1
                                } else if (mode == 2) {
                                    vm.endResize()
                                    mode = 1
                                } else if (mode == 4 || mode == 6) {
                                    if (mode == 6) vm.endTransform()
                                    mode = 1
                                }
                                magnifierPoint = null
                                val zoom = event.calculateZoom()
                                val rotation = event.calculateRotation()
                                val pan = event.calculatePan()
                                if (chrome is TextChromeHit.Rotate) {
                                    // Two fingers on the rotate handle: uniform
                                    // scale and rotate together, never move.
                                    vm.transformTextLayer(
                                        Offset.Zero,
                                        zoom.coerceIn(0.5f, 2f),
                                        rotation,
                                        snapEnabled = false
                                    )
                                } else if (vm.tool == Tool.MOVE ||
                                    (mode == 1 && chrome != null) ||
                                    mode == 6
                                ) {
                                    vm.transformActive(pan / viewScale, zoom, rotation)
                                } else {
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val old = viewOffset
                                    viewScale = (viewScale * zoom).coerceIn(0.02f, 12f)
                                    viewOffset = centroid + (old - centroid) * zoom + pan
                                }
                                event.changes.forEach { it.consume() }
                            } else if (mode == 2) {
                                val change = event.changes.firstOrNull { it.pressed } ?: continue
                                vm.resizeActiveTo(toDoc(change.position))
                                last = change.position
                                change.consume()
                            } else if (mode == 3) {
                                val change = event.changes.firstOrNull { it.pressed } ?: continue
                                vm.extendLasso(toDoc(change.position))
                                last = change.position
                                change.consume()
                            } else if (mode == 4) {
                                val change = event.changes.firstOrNull { it.pressed } ?: continue
                                val active = doc.active
                                if (active != null && active.kind == LayerKind.TEXT) {
                                    if (chrome is TextChromeHit.Rotate) {
                                        // Rotate around the text center; the angle
                                        // between the start and current vectors,
                                        // plus a drag radius change for scale.
                                        // Snapping stays off: a rotate must never
                                        // translate the layer.
                                        val quad = layerScreenQuad(active, viewOffset, viewScale)
                                        val center = Offset(
                                            (quad[0].x + quad[2].x) / 2f,
                                            (quad[0].y + quad[2].y) / 2f
                                        )
                                        val startVector = chromeStart - center
                                        val currentVector = change.position - center
                                        val startAngle = atan2(startVector.y, startVector.x)
                                        val currentAngle = atan2(currentVector.y, currentVector.x)
                                        var delta = (currentAngle - startAngle) * 180f / PI.toFloat()
                                        if (delta > 180f) delta -= 360f
                                        if (delta < -180f) delta += 360f
                                        val startRadius = startVector.getDistance()
                                        val currentRadius = currentVector.getDistance()
                                        val scale = if (startRadius > 1f) {
                                            (currentRadius / startRadius).coerceIn(0.5f, 2f)
                                        } else 1f
                                        vm.transformTextLayer(Offset.Zero, scale, delta, snapEnabled = false)
                                        chromeStart = change.position
                                    } else {
                                        vm.transformTextLayer(
                                            (change.position - last) / viewScale,
                                            1f,
                                            0f
                                        )
                                    }
                                    if ((change.position - down.position).getDistance() > minSizePx) {
                                        moved = true
                                    }
                                }
                                last = change.position
                                change.consume()
                            } else if (mode == 6) {
                                // Body touch: a clean tap opens the editor (and
                                // switches to the Text tool from Move mode); a
                                // drag transforms the layer.
                                val change = event.changes.firstOrNull { it.pressed } ?: continue
                                if ((change.position - down.position).getDistance() > minSizePx) {
                                    if (!moved) {
                                        vm.beginTextTransform()
                                    }
                                    moved = true
                                    vm.transformTextLayer(
                                        (change.position - last) / viewScale,
                                        1f,
                                        0f
                                    )
                                }
                                last = change.position
                                change.consume()
                            } else if (mode == 5) {
                                val change = event.changes.firstOrNull { it.pressed } ?: continue
                                if ((change.position - down.position).getDistance() > minSizePx) {
                                    moved = true
                                    chromeButton = null
                                }
                                change.consume()
                            } else if (mode == 0) {
                                val change = event.changes.firstOrNull { it.pressed } ?: continue
                                val position = change.position
                                if ((position - down.position).getDistance() > minSizePx) moved = true
                                if (vm.tool == Tool.PAN) {
                                    viewOffset += (position - last)
                                } else {
                                    vm.strokeMove(toDoc(position))
                                    if (vm.tool == Tool.INPAINT || vm.tool == Tool.BRUSH || vm.tool == Tool.ERASER) {
                                        magnifierPoint = position
                                    }
                                }
                                last = position
                                change.consume()
                            }
                        }

                        when (mode) {
                            0 -> if (vm.tool != Tool.PAN) {
                                vm.strokeEnd(toDoc(last))
                                if (!moved) {
                                    if (vm.tool == Tool.INPAINT && vm.removeMode == RemoveMode.TAP) {
                                        vm.addSmartTap(toDoc(last))
                                    } else {
                                        vm.tap(toDoc(last))
                                    }
                                }
                            }
                            1 -> if (vm.tool == Tool.MOVE) vm.endTransform()
                            2 -> vm.endResize()
                            3 -> vm.finishLasso()
                            4 -> vm.endTransform()
                            5 -> chromeButton?.invoke()
                            6 -> {
                                if (moved) {
                                    vm.endTransform()
                                } else {
                                    // Clean tap on the chrome body.
                                    vm.endTransform()
                                    (chrome as? TextChromeHit.Body)?.let {
                                        vm.tapTextLayer(it.layerId)
                                    }
                                }
                            }
                        }
                        magnifierPoint = null
                    }
                }
        ) {
            // read this so in-place bitmap edits invalidate the draw pass
            @Suppress("UNUSED_VARIABLE")
            val repaint = vm.repaintTick

            val docRect = Rect(
                offset = viewOffset,
                size = Size(doc.width * viewScale, doc.height * viewScale)
            )

            drawRect(Color(0xFF17181C), size = size)

            if (docRect.width > 0f && docRect.height > 0f) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = docRect.topLeft + Offset(6f, 10f),
                    size = docRect.size
                )
                clipRect(docRect.left, docRect.top, docRect.right, docRect.bottom) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.drawRect(
                            docRect.left, docRect.top, docRect.right, docRect.bottom,
                            Checker.paint()
                        )
                    }
                }
            }

            withTransform({
                translate(viewOffset.x, viewOffset.y)
                scale(viewScale, viewScale, pivot = Offset.Zero)
            }) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
                    Compositor.drawDocument(native, doc)
                    native.restore()
                }
            }

            if (showGrid && docRect.width > 0f) {
                val lineColor = Color.White.copy(alpha = 0.18f)
                for (i in 1..2) {
                    val x = docRect.left + docRect.width * i / 3f
                    val y = docRect.top + docRect.height * i / 3f
                    drawLine(lineColor, Offset(x, docRect.top), Offset(x, docRect.bottom), 1f)
                    drawLine(lineColor, Offset(docRect.left, y), Offset(docRect.right, y), 1f)
                }
            }

            // Painted object-removal mask, shown only while a mask-based Remove
            // mode is active (background cutout needs no mask).
            if (vm.tool == Tool.INPAINT && vm.removeMode != RemoveMode.BACKGROUND) {
                vm.inpaintMask?.let { mask ->
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawBitmap(
                            mask,
                            null,
                            android.graphics.RectF(
                                docRect.left,
                                docRect.top,
                                docRect.right,
                                docRect.bottom
                            ),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        )
                    }
                }
            }

            // Freehand lasso in progress: the loop plus a preview of the rectangle it will fill.
            vm.inpaintLasso?.takeIf { vm.tool == Tool.INPAINT && it.size >= 2 }?.let { lasso ->
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                val path = Path()
                lasso.forEachIndexed { index, point ->
                    if (point.x < minX) minX = point.x
                    if (point.y < minY) minY = point.y
                    if (point.x > maxX) maxX = point.x
                    if (point.y > maxY) maxY = point.y
                    val screen = viewOffset + point * viewScale
                    if (index == 0) path.moveTo(screen.x, screen.y)
                    else path.lineTo(screen.x, screen.y)
                }
                drawPath(
                    path,
                    color = Color.Black.copy(alpha = 0.5f),
                    style = Stroke(width = 5f)
                )
                drawPath(path, color = accent, style = Stroke(width = 2f))

                val topLeft = viewOffset + Offset(minX, minY) * viewScale
                val bottomRight = viewOffset + Offset(maxX, maxY) * viewScale
                val boxSize = Size(
                    (bottomRight.x - topLeft.x).coerceAtLeast(1f),
                    (bottomRight.y - topLeft.y).coerceAtLeast(1f)
                )
                drawRect(
                    color = accent.copy(alpha = 0.10f),
                    topLeft = topLeft,
                    size = boxSize
                )
                drawRect(
                    color = accent.copy(alpha = 0.9f),
                    topLeft = topLeft,
                    size = boxSize,
                    style = Stroke(
                        width = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
                    )
                )
            }

            // Magnified view above the finger while painting with the Remove
            // brush or the paint brush / eraser.
            val finger = magnifierPoint
            if (finger != null && (vm.tool == Tool.BRUSH || vm.tool == Tool.ERASER)) {
                val radius = with(density) { 58.dp.toPx() }
                val gap = with(density) { 28.dp.toPx() }
                val margin = with(density) { 10.dp.toPx() }
                val zoom = 2.6f
                val docPoint = toDoc(finger)
                var centerX = finger.x
                var centerY = finger.y - radius - gap
                // Near the top edge the bubble opens below the finger instead.
                if (centerY - radius < margin) centerY = finger.y + radius + gap
                val minX = radius + margin
                val maxX = (size.width - radius - margin).coerceAtLeast(minX)
                val minY = radius + margin
                val maxY = (size.height - radius - margin).coerceAtLeast(minY)
                centerX = centerX.coerceIn(minX, maxX)
                centerY = centerY.coerceIn(minY, maxY)
                val center = Offset(centerX, centerY)
                val loupeScale = viewScale * zoom
                val loupeOffset = center - docPoint * loupeScale

                clipPath(Path().apply { addOval(Rect(center = center, radius = radius)) }) {
                    drawRect(Color(0xFF17181C), size = size)
                    withTransform({
                        translate(loupeOffset.x, loupeOffset.y)
                        scale(loupeScale, loupeScale, pivot = Offset.Zero)
                    }) {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            native.save()
                            native.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
                            native.drawRect(
                                0f, 0f, doc.width.toFloat(), doc.height.toFloat(), Checker.paint()
                            )
                            Compositor.drawDocument(native, doc)
                            native.restore()
                        }
                    }
                }
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = radius + 2f,
                    center = center,
                    style = Stroke(width = 3f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2f)
                )
                val arm = with(density) { 6.dp.toPx() }
                drawLine(
                    Color.White.copy(alpha = 0.8f),
                    center - Offset(arm, 0f),
                    center + Offset(arm, 0f),
                    strokeWidth = 1.5f
                )
                drawLine(
                    Color.White.copy(alpha = 0.8f),
                    center - Offset(0f, arm),
                    center + Offset(0f, arm),
                    strokeWidth = 1.5f
                )
            }
            if (finger != null && vm.tool == Tool.INPAINT && vm.removeMode == RemoveMode.BRUSH) {
                val radius = with(density) { 58.dp.toPx() }
                val gap = with(density) { 28.dp.toPx() }
                val margin = with(density) { 10.dp.toPx() }
                val zoom = 2.6f
                val docPoint = toDoc(finger)
                var centerX = finger.x
                var centerY = finger.y - radius - gap
                // Near the top edge the bubble opens below the finger instead.
                if (centerY - radius < margin) centerY = finger.y + radius + gap
                val minX = radius + margin
                val maxX = (size.width - radius - margin).coerceAtLeast(minX)
                val minY = radius + margin
                val maxY = (size.height - radius - margin).coerceAtLeast(minY)
                centerX = centerX.coerceIn(minX, maxX)
                centerY = centerY.coerceIn(minY, maxY)
                val center = Offset(centerX, centerY)
                val loupeScale = viewScale * zoom
                val loupeOffset = center - docPoint * loupeScale

                clipPath(Path().apply { addOval(Rect(center = center, radius = radius)) }) {
                    drawRect(Color(0xFF17181C), size = size)
                    withTransform({
                        translate(loupeOffset.x, loupeOffset.y)
                        scale(loupeScale, loupeScale, pivot = Offset.Zero)
                    }) {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            native.save()
                            native.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
                            native.drawRect(
                                0f, 0f, doc.width.toFloat(), doc.height.toFloat(), Checker.paint()
                            )
                            Compositor.drawDocument(native, doc)
                            vm.inpaintMask?.let { mask ->
                                native.drawBitmap(
                                    mask,
                                    null,
                                    android.graphics.RectF(
                                        0f, 0f, doc.width.toFloat(), doc.height.toFloat()
                                    ),
                                    android.graphics.Paint(
                                        android.graphics.Paint.FILTER_BITMAP_FLAG
                                    )
                                )
                            }
                            native.restore()
                        }
                    }
                }
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = radius + 2f,
                    center = center,
                    style = Stroke(width = 3f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2f)
                )
                val arm = with(density) { 6.dp.toPx() }
                drawLine(
                    Color.White.copy(alpha = 0.8f),
                    center - Offset(arm, 0f),
                    center + Offset(arm, 0f),
                    strokeWidth = 1.5f
                )
                drawLine(
                    Color.White.copy(alpha = 0.8f),
                    center - Offset(0f, arm),
                    center + Offset(0f, arm),
                    strokeWidth = 1.5f
                )
            }

            // Snap guides while a layer drag sticks to a canvas centerline.
            if (vm.snapVertical) {
                drawLine(
                    accent,
                    Offset(docRect.center.x, docRect.top),
                    Offset(docRect.center.x, docRect.bottom),
                    strokeWidth = 1.5f
                )
            }
            if (vm.snapHorizontal) {
                drawLine(
                    accent,
                    Offset(docRect.left, docRect.center.y),
                    Offset(docRect.right, docRect.center.y),
                    strokeWidth = 1.5f
                )
            }

            // Active layer outline while moving, plus resize grips in resize mode.
            // Text layers show the full selection chrome in Move and Text modes.
            if (vm.tool == Tool.MOVE || vm.tool == Tool.RESIZE || vm.tool == Tool.TEXT) {
                doc.active?.takeIf { it.visible }?.let { layer ->
                    val quad = layerScreenQuad(layer, viewOffset, viewScale)
                    val isText = layer.kind == LayerKind.TEXT
                    val path = Path().apply {
                        moveTo(quad[0].x, quad[0].y)
                        lineTo(quad[1].x, quad[1].y)
                        lineTo(quad[2].x, quad[2].y)
                        lineTo(quad[3].x, quad[3].y)
                        close()
                    }
                    drawPath(
                        path,
                        color = accent,
                        style = Stroke(
                            width = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                        )
                    )

                    if (isText) {
                        // Text selection chrome: ✕ duplicate rotate, floating
                        // outside the box corners (see chromeCorners).
                        val corners = chromeCorners(layer, viewScale, viewOffset, density)
                        val radius = with(density) { (CHROME_BUTTON_DP / 2f).toPx() }
                        drawTextButton(corners[0].center, radius, Icons.Filled.Close, density)
                        drawTextButton(corners[1].center, radius, Icons.Filled.ContentCopy, density)
                        drawTextButton(corners[2].center, radius, Icons.Filled.Autorenew, density)
                    }

                    if (vm.tool != Tool.RESIZE) return@let

                    // Four corner grips keep the aspect ratio; four edge grips resize
                    // a single axis. Both are drawn in screen space so they stay the
                    // same physical size at any zoom.
                    for ((handle, point) in layer.handlePositions()) {
                        val center = viewOffset + point * viewScale
                        val shadow = Color.Black.copy(alpha = 0.6f)
                        if (handle.isCorner) {
                            val half = cornerGripPx / 2f
                            drawRoundRect(
                                color = shadow,
                                topLeft = Offset(center.x - half - 1.5f, center.y - half - 1.5f),
                                size = Size(cornerGripPx + 3f, cornerGripPx + 3f),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(center.x - half, center.y - half),
                                size = Size(cornerGripPx, cornerGripPx),
                                cornerRadius = CornerRadius(2f, 2f)
                            )
                        } else {
                            drawCircle(shadow, radius = edgeGripPx / 2f + 1.5f, center = center)
                            drawCircle(Color.White, radius = edgeGripPx / 2f, center = center)
                        }
                    }
                }
            }

            // Crop overlay
            vm.cropRect?.let { crop ->
                if (vm.tool == Tool.CROP) {
                    val tl = viewOffset + Offset(crop.left * viewScale, crop.top * viewScale)
                    val br = viewOffset + Offset(crop.right * viewScale, crop.bottom * viewScale)
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, Offset(0f, 0f), Size(size.width, tl.y.coerceAtLeast(0f)))
                    drawRect(dim, Offset(0f, br.y), Size(size.width, (size.height - br.y).coerceAtLeast(0f)))
                    drawRect(dim, Offset(0f, tl.y), Size(tl.x.coerceAtLeast(0f), (br.y - tl.y).coerceAtLeast(0f)))
                    drawRect(
                        dim,
                        Offset(br.x, tl.y),
                        Size((size.width - br.x).coerceAtLeast(0f), (br.y - tl.y).coerceAtLeast(0f))
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = tl,
                        size = Size(br.x - tl.x, br.y - tl.y),
                        style = Stroke(width = 1.5f)
                    )
                    val handle = 14f
                    listOf(tl, Offset(br.x, tl.y), br, Offset(tl.x, br.y)).forEach { corner ->
                        drawRect(
                            Color.White,
                            topLeft = Offset(corner.x - handle / 2, corner.y - handle / 2),
                            size = Size(handle, handle)
                        )
                    }
                }
            }

            // Shape preview
            val start = vm.shapeStart
            val end = vm.shapeEnd
            if (vm.tool == Tool.SHAPE && start != null && end != null) {
                val a = viewOffset + start * viewScale
                val b = viewOffset + end * viewScale
                val stroke = Stroke(width = 2f)
                when (vm.shapeKind) {
                    ShapeKind.LINE -> drawLine(
                        accent, a, b, strokeWidth = 2f
                    )
                    ShapeKind.RECT -> {
                        val rect = Rect(
                            min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)
                        )
                        if (vm.shapeFilled) {
                            drawRect(
                                Color(vm.color).copy(alpha = 0.5f),
                                topLeft = rect.topLeft,
                                size = rect.size
                            )
                        }
                        drawRect(accent, rect.topLeft, rect.size, style = stroke)
                    }
                    ShapeKind.ELLIPSE -> {
                        val rect = Rect(
                            min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)
                        )
                        if (vm.shapeFilled) {
                            drawOval(
                                Color(vm.color).copy(alpha = 0.5f),
                                topLeft = rect.topLeft,
                                size = rect.size
                            )
                        }
                        drawOval(accent, rect.topLeft, rect.size, style = stroke)
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(50))
        ) {
            Row(
                Modifier.pointerInput(Unit) { detectTapGesturesCompat { } },
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillAction(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    label = "Undo",
                    enabled = vm.canUndo,
                    onClick = vm::undo
                )
                PillAction(
                    icon = Icons.AutoMirrored.Filled.Redo,
                    label = "Redo",
                    enabled = vm.canRedo,
                    onClick = vm::redo
                )
                PillAction(
                    icon = Icons.Filled.ZoomOut,
                    label = "Zoom out",
                    enabled = viewScale > fitScale() * MIN_ZOOM_RATIO + 0.0001f,
                    onClick = {
                        // Uniform 1.25x steps in the fit-anchored percent domain,
                        // so increments feel equal at any zoom level.
                        zoomTo(viewScale / ZOOM_STEP)
                    }
                )
                Text(
                    "${(viewScale / fitScale() * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .pointerInput(Unit) {
                            detectTapGesturesCompat { zoomTo(fitScale()) }
                        }
                        .padding(start = 4.dp, end = 4.dp, top = 7.dp, bottom = 7.dp)
                )
                PillAction(
                    icon = Icons.Filled.ZoomIn,
                    label = "Zoom in",
                    enabled = viewScale < fitScale() * MAX_ZOOM_RATIO - 0.0001f,
                    onClick = {
                        zoomTo(viewScale * ZOOM_STEP)
                    }
                )
            }
        }
    }
}

/** Canvas scale that fits the document with the standard 0.9 margin. */
private fun computeFit(viewport: IntSize, doc: Document): Float =
    min(
        viewport.width / doc.width.toFloat(),
        viewport.height / doc.height.toFloat()
    ) * 0.9f

/**
 * One floating chrome button: white disc, black glyph drawn with simple
 * geometry (an ✕, an overlapping-rectangles copy mark, or circular rotate
 * arrows). Drawn on the native canvas because ImageVectors need a compose
 * draw scope translation that is awkward inside DrawTransform blocks.
 */
private fun DrawScope.drawTextButton(
    center: Offset,
    radius: Float,
    kind: ImageVector,
    density: Density
) {
    drawCircle(Color.White, radius = radius + 1.5f, center = center)
    drawCircle(
        Color.Black.copy(alpha = 0.25f),
        radius = radius + 2.5f,
        center = center,
        style = Stroke(width = 2f)
    )
    val glyph = radius * 0.5f
    when (kind) {
        Icons.Filled.Close -> {
            drawLine(Color.Black, center - Offset(glyph, glyph), center + Offset(glyph, glyph), 2.5f)
            drawLine(Color.Black, center - Offset(glyph, -glyph), center + Offset(glyph, -glyph), 2.5f)
        }
        Icons.Filled.ContentCopy -> {
            // Two overlapping rounded rectangles.
            val s = glyph * 2f
            drawRoundRect(
                Color.Black,
                topLeft = center + Offset(-s / 2f, -s / 2f) + Offset(s * 0.28f, -s * 0.28f),
                size = Size(s * 0.75f, s * 0.75f),
                cornerRadius = CornerRadius(2f, 2f),
                style = Stroke(width = 2.5f)
            )
            drawRoundRect(
                Color.White,
                topLeft = center + Offset(-s / 2f, -s / 2f) - Offset(s * 0.02f, s * 0.02f),
                size = Size(s * 0.75f, s * 0.75f),
                cornerRadius = CornerRadius(2f, 2f)
            )
            drawRoundRect(
                Color.Black,
                topLeft = center + Offset(-s / 2f, -s / 2f) - Offset(s * 0.02f, s * 0.02f),
                size = Size(s * 0.75f, s * 0.75f),
                cornerRadius = CornerRadius(2f, 2f),
                style = Stroke(width = 2.5f)
            )
        }
        else -> {
            // Rotate: a three-quarter arc with an arrowhead.
            val arcRect = Rect(center - Offset(glyph, glyph), Size(glyph * 2f, glyph * 2f))
            drawArc(
                Color.Black,
                startAngle = -60f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = arcRect.topLeft,
                size = arcRect.size,
                style = Stroke(width = 2.5f)
            )
            val tipAngle = (-60f + 280f) * PI.toFloat() / 180f
            val tipDir = Offset(cos(tipAngle), sin(tipAngle))
            val tip = center + tipDir * glyph
            drawPath(
                Path().apply {
                    moveTo(tip.x + tipDir.x * glyph * 0.35f, tip.y + tipDir.y * glyph * 0.35f)
                    lineTo(tip.x - tipDir.y * glyph * 0.3f, tip.y + tipDir.x * glyph * 0.3f)
                    lineTo(tip.x + tipDir.y * glyph * 0.3f, tip.y - tipDir.x * glyph * 0.3f)
                    close()
                },
                Color.Black
            )
        }
    }
}

/** Radius of a floating chrome button, in dp. 24 dp radius = 48 dp target. */
private val CHROME_BUTTON_DP = 24.dp
/** Distance from the box corner to the button center, in dp. */
private val CHROME_BUTTON_GAP_DP = 26.dp

/** What a screen point hit on the text selection chrome. */
private sealed class TextChromeHit {
    abstract val layerId: Long

    data class Delete(override val layerId: Long) : TextChromeHit()
    data class Duplicate(override val layerId: Long) : TextChromeHit()
    data class Rotate(override val layerId: Long) : TextChromeHit()
    data class Body(override val layerId: Long) : TextChromeHit()
}

private data class ChromeCorner(val center: Offset)

/** The three floating buttons of the chrome, outside the box corners. */
private fun chromeCorners(
    layer: Layer,
    viewScale: Float,
    viewOffset: Offset,
    density: Density
): List<ChromeCorner> {
    val quad = layerScreenQuad(layer, viewOffset, viewScale)
    val gapPx = with(density) { CHROME_BUTTON_GAP_DP.toPx() }
    // Outward direction from the box center, so buttons float outside corners.
    val cx = (quad[0].x + quad[2].x) / 2f
    val cy = (quad[0].y + quad[2].y) / 2f
    fun outward(corner: Offset): Offset {
        val dx = corner.x - cx
        val dy = corner.y - cy
        val len = maxOf(0.001f, sqrt(dx * dx + dy * dy))
        return Offset(dx / len, dy / len) * gapPx
    }
    return listOf(
        ChromeCorner(quad[0] + outward(quad[0])),   // top-left: delete
        ChromeCorner(quad[1] + outward(quad[1])),   // top-right: duplicate
        ChromeCorner(quad[2] + outward(quad[2]))    // bottom-right: rotate
    )
}

/**
 * Screen-space hit test against the text selection chrome (body + floating
 * buttons) of the active text layer.
 *
 * The box body wins inside the box — a grab there is always a move — and the
 * floating buttons are checked *outside* it, nearest first. That keeps the
 * gestures disjoint: the handle can never swallow a body drag and vice versa,
 * regardless of box size or rotation.
 */
private fun textChromeHitTest(
    doc: Document,
    docPoint: Offset,
    density: Density,
    viewScale: Float,
    viewOffset: Offset
): TextChromeHit? {
    val layer = doc.active ?: return null
    if (layer.kind != LayerKind.TEXT) return null
    val screen = viewOffset + docPoint * viewScale
    val local = layer.toLayerSpace(docPoint)
    if (local.x >= 0f && local.y >= 0f &&
        local.x <= layer.bitmap.width && local.y <= layer.bitmap.height
    ) {
        return TextChromeHit.Body(layer.id)
    }
    val hitRadius = with(density) { (CHROME_BUTTON_DP / 2f + 4.dp).toPx() }
    val corners = chromeCorners(layer, viewScale, viewOffset, density)
    var best = -1
    var bestDistance = Float.MAX_VALUE
    for (index in corners.indices) {
        val distance = screen.getDistanceTo(corners[index].center)
        if (distance <= hitRadius && distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return when (best) {
        0 -> TextChromeHit.Delete(layer.id)
        1 -> TextChromeHit.Duplicate(layer.id)
        2 -> TextChromeHit.Rotate(layer.id)
        else -> null
    }
}

private fun Offset.getDistanceTo(other: Offset): Float = (this - other).getDistance()

/** Zoom pill bounds, expressed relative to the starting fit (25%..800%). */
private const val MIN_ZOOM_RATIO = 0.25f
private const val MAX_ZOOM_RATIO = 8f

/** Multiplicative step of the zoom buttons; 1.25x reads as "fine" next to pinch. */
private const val ZOOM_STEP = 1.25f

@Composable
private fun PillAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.3f),
            modifier = Modifier.size(19.dp)
        )
    }
}

private fun layerScreenQuad(layer: Layer, offset: Offset, scale: Float): List<Offset> {
    val pts = floatArrayOf(
        0f, 0f,
        layer.width.toFloat(), 0f,
        layer.width.toFloat(), layer.height.toFloat(),
        0f, layer.height.toFloat()
    )
    layer.matrix().mapPoints(pts)
    return (0 until 4).map { i ->
        Offset(
            offset.x + pts[i * 2] * scale,
            offset.y + pts[i * 2 + 1] * scale
        )
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(
    onTap: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            if (event.changes.any { (it.position - down.position).getDistance() > 20f }) moved = true
        }
        if (!moved) onTap()
    }
}
