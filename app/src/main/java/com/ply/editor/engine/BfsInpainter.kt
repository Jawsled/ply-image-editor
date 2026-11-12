package com.ply.editor.engine

import android.graphics.Bitmap

/**
 * The original, pre-ONNX object-removal fill: a multi-source BFS "nearest color"
 * fill followed by a few box-smoothing passes. No neural network, no native
 * dependency, linear time — the light option for low-end devices and quick
 * touch-ups. The approach follows [EmreO33/unmark](https://github.com/EmreO33/unmark);
 * this implementation is original Kotlin.
 *
 * 1. Every masked pixel takes the color of the closest unmasked pixel (a BFS
 *    from all known pixels at once, cheap and deterministic).
 * 2. A few averaging passes over just the masked region soften the hard BFS
 *    boundaries so the fill doesn't look blocky.
 *
 * Smooths toward near-constant color inside the hole — best on marks, logos and
 * other thin selections on reasonably textured backgrounds. Both bitmaps must
 * have the same size. Returns a bitmap where painted pixels carry the filled
 * color with full alpha and everything else is transparent — the same engine
 * contract as the other engines.
 */
object BfsInpainter {

    private const val SMOOTHING_PASSES = 6

    fun inpaint(source: Bitmap, mask: Bitmap): Bitmap? = runCatching {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0 || mask.width != w || mask.height != h) return@runCatching null

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)

        val masked = BooleanArray(w * h)
        var holes = 0
        for (i in masked.indices) {
            masked[i] = (maskPixels[i] ushr 24) > 8
            if (masked[i]) holes++
        }
        if (holes == 0 || holes == masked.size) return@runCatching null

        nearestFill(pixels, masked, w, h)
        smooth(pixels, masked, w, h, SMOOTHING_PASSES)

        // Composite: filled pixels opaque, everything else transparent.
        for (i in pixels.indices) {
            pixels[i] = if (masked[i]) {
                (pixels[i] and 0x00FFFFFF) or (0xFF shl 24)
            } else {
                0
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        out
    }.getOrNull()

    /** Multi-source BFS: every masked pixel takes the color of the closest unmasked one. */
    private fun nearestFill(pixels: IntArray, masked: BooleanArray, w: Int, h: Int) {
        val n = w * h
        // Each pixel enters the queue exactly once, so a flat array is enough.
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        val seen = BooleanArray(n)
        for (i in 0 until n) {
            if (!masked[i]) {
                seen[i] = true
                queue[tail++] = i
            }
        }
        if (tail == 0) return
        while (head < tail) {
            val idx = queue[head++]
            val color = pixels[idx]
            val x = idx % w
            val y = idx / w
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val neighbor = ny * w + nx
                    if (seen[neighbor]) continue
                    seen[neighbor] = true
                    if (masked[neighbor]) pixels[neighbor] = color
                    queue[tail++] = neighbor
                }
            }
        }
    }

    /** Average each masked pixel with its neighbours a few times to soften BFS block edges. */
    private fun smooth(pixels: IntArray, masked: BooleanArray, w: Int, h: Int, passes: Int) {
        var current = pixels
        var next = IntArray(pixels.size)
        repeat(passes) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!masked[i]) {
                        next[i] = current[i]
                        continue
                    }
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                            val c = current[ny * w + nx]
                            a += (c ushr 24) and 0xFF
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                    }
                    next[i] = if (count > 0) {
                        ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                    } else {
                        current[i]
                    }
                }
            }
            val swap = current
            current = next
            next = swap
        }
        if (current !== pixels) System.arraycopy(current, 0, pixels, 0, pixels.size)
    }
}
