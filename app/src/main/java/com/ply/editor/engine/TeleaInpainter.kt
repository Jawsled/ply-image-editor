package com.ply.editor.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Telea inpainting — "An Image Inpainting Technique Based on the Fast Marching
 * Method" (Telea, 2004) — with no neural network and no runtime dependency.
 * Kotlin port of the TypeScript implementation in
 * [maxgfr/unmark](https://github.com/maxgfr/unmark) (src/image/inpaint/telea.ts,
 * MIT License), one of Ply's no-AI object-removal engines.
 *
 * The method fills the marked region from its boundary inwards, always taking
 * the pixel closest to known territory next. Each pixel is a weighted average
 * of the known pixels around it, where the weights prefer neighbours that are
 * close, on the same level set of the arrival time, and aligned with the
 * direction the front is travelling — which is what continues an edge into the
 * hole instead of blurring across it.
 *
 * Two cost controls keep it fast on phones: the fill runs on a half-resolution
 * copy of the crop (Telea fills are smooth, so the bilinear upscale back
 * through the full-resolution mask is invisible in practice), and the
 * averaging radius is 3 — OpenCV's own default — which at half resolution
 * still covers more source context than radius 5 at full resolution. The
 * neighbourhood window is fixed, so every distance-dependent weight factor is
 * precomputed and the hot loop does no sqrt.
 *
 * Both bitmaps must have the same size. Returns a bitmap where painted pixels
 * carry the filled color with full alpha and everything else is transparent —
 * the same engine contract as the other engines.
 */
object TeleaInpainter {

    private const val KNOWN: Byte = 0
    private const val BAND: Byte = 1
    private const val INSIDE: Byte = 2
    private const val INF = 1e6f

    /** Radius of the neighbourhood each filled pixel averages over. */
    private const val RADIUS = 3

    /** Longest edge of the copy the fast marching actually runs on. */
    private const val LOW_RES_EDGE = 512

    /** One neighbourhood offset with its distance-dependent weight factors. */
    private class NeighborOffset(
        val dx: Int,
        val dy: Int,
        /** -dx / distance and -dy / distance, so the loop does no sqrt per neighbour. */
        val rxNorm: Float,
        val ryNorm: Float,
        /** Raw unit direction used for gradient continuation. */
        val rx: Float,
        val ry: Float,
        val invDistSq: Float
    )

    private val OFFSETS: List<NeighborOffset> = buildList {
        for (dy in -RADIUS..RADIUS) {
            for (dx in -RADIUS..RADIUS) {
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared == 0 || distanceSquared > RADIUS * RADIUS) continue
                val distance = sqrt(distanceSquared.toFloat())
                add(
                    NeighborOffset(
                        dx = dx,
                        dy = dy,
                        rxNorm = -dx.toFloat() / distance,
                        ryNorm = -dy.toFloat() / distance,
                        rx = -dx.toFloat(),
                        ry = -dy.toFloat(),
                        invDistSq = 1f / distanceSquared
                    )
                )
            }
        }
    }

    fun inpaint(source: Bitmap, mask: Bitmap): Bitmap? = runCatching {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0 || mask.width != w || mask.height != h) return@runCatching null

        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)

        var holes = 0
        for (i in maskPixels.indices) {
            if ((maskPixels[i] ushr 24) > 8) holes++
        }
        if (holes == 0 || holes == maskPixels.size) return@runCatching null

        // Small crops fill at full resolution; larger ones run at half the edge
        // length and the fill is scaled back up, which keeps the fill's cost —
        // hole pixels times the neighbourhood window — comfortably small.
        return@runCatching if (maxOf(w, h) <= LOW_RES_EDGE) {
            fill(srcPixels, maskPixels, w, h)
            composite(srcPixels, maskPixels, w, h)
        } else {
            val scale = LOW_RES_EDGE / maxOf(w, h).toFloat()
            val lw = (w * scale).roundToInt().coerceAtLeast(1)
            val lh = (h * scale).roundToInt().coerceAtLeast(1)
            val (lowPixels, lowMask) = downsample(srcPixels, maskPixels, w, h, lw, lh)
            fill(lowPixels, lowMask, lw, lh)
            val lowBitmap = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
            lowBitmap.setPixels(lowPixels, 0, lw, 0, 0, lw, lh)
            val upscaled = Bitmap.createScaledBitmap(lowBitmap, w, h, true)
            lowBitmap.recycle()
            val upscaledPixels = IntArray(w * h)
            upscaled.getPixels(upscaledPixels, 0, w, 0, 0, w, h)
            upscaled.recycle()
            composite(upscaledPixels, maskPixels, w, h)
        }
    }.getOrNull()

    /**
     * March the front over [pixels] and fill every hole in place. Known pixels
     * keep their color, holes take the inpainted value. Internal for tests.
     */
    internal fun fill(pixels: IntArray, maskPixels: IntArray, w: Int, h: Int) {
        val flag = ByteArray(w * h)
        val time = FloatArray(w * h)
        var holes = 0
        for (i in flag.indices) {
            if ((maskPixels[i] ushr 24) > 8) {
                flag[i] = INSIDE
                time[i] = INF
                holes++
            }
        }
        if (holes == 0 || holes == flag.size) return

        // The initial front: known pixels touching a hole.
        val front = Front()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (flag[y * w + x] != INSIDE) continue
                for (d in 0..3) {
                    val nxp = x + DX[d]
                    val nyp = y + DY[d]
                    if (nxp < 0 || nyp < 0 || nxp >= w || nyp >= h) continue
                    val n = nyp * w + nxp
                    if (flag[n] == KNOWN) {
                        flag[n] = BAND
                        time[n] = 0f
                        front.push(0f, nxp, nyp)
                    }
                }
            }
        }

        // March: pop the reached pixel nearest known territory, fill its hole
        // neighbours, and repeat until the hole is gone.
        while (front.isNotEmpty) {
            front.pop()
            val px = front.popX
            val py = front.popY
            flag[py * w + px] = KNOWN
            for (d in 0..3) {
                val nxp = px + DX[d]
                val nyp = py + DY[d]
                if (nxp < 0 || nyp < 0 || nxp >= w || nyp >= h) continue
                val n = nyp * w + nxp
                if (flag[n] != INSIDE) continue
                val arrival = minOf(
                    solve(flag, time, w, h, nxp - 1, nyp, nxp, nyp - 1),
                    solve(flag, time, w, h, nxp + 1, nyp, nxp, nyp - 1),
                    solve(flag, time, w, h, nxp - 1, nyp, nxp, nyp + 1),
                    solve(flag, time, w, h, nxp + 1, nyp, nxp, nyp + 1)
                )
                time[n] = arrival
                pixels[n] = estimate(pixels, flag, time, w, h, nxp, nyp)
                flag[n] = BAND
                front.push(arrival, nxp, nyp)
            }
        }
    }

    /**
     * Box-average the source and mark a coarse cell as hole when any of its
     * children is hole, so the upscaled fill covers the whole marked area.
     * Known cells average only their known children. Internal for tests.
     */
    internal fun downsample(
        src: IntArray,
        msk: IntArray,
        w: Int,
        h: Int,
        lw: Int,
        lh: Int
    ): Pair<IntArray, IntArray> {
        val out = IntArray(lw * lh)
        val outMask = IntArray(lw * lh)
        for (cy in 0 until lh) {
            val y0 = cy * h / lh
            val y1 = maxOf(y0 + 1, (cy + 1) * h / lh)
            for (cx in 0 until lw) {
                val x0 = cx * w / lw
                val x1 = maxOf(x0 + 1, (cx + 1) * w / lw)
                var r = 0L
                var g = 0L
                var b = 0L
                var count = 0
                var anyHole = false
                for (y in y0 until minOf(y1, h)) {
                    for (x in x0 until minOf(x1, w)) {
                        val i = y * w + x
                        if ((msk[i] ushr 24) > 8) {
                            anyHole = true
                            continue
                        }
                        val c = src[i]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                }
                val j = cy * lw + cx
                outMask[j] = if (anyHole) 0xFF000000.toInt() else 0
                out[j] = if (count > 0) {
                    (0xFF shl 24) or
                        ((r / count).toInt() shl 16) or
                        ((g / count).toInt() shl 8) or
                        (b / count).toInt()
                } else {
                    0xFF000000.toInt()
                }
            }
        }
        return out to outMask
    }

    /** Filled pixels opaque, everything else transparent. */
    private fun composite(pixels: IntArray, maskPixels: IntArray, w: Int, h: Int): Bitmap {
        val outPixels = IntArray(pixels.size)
        for (i in outPixels.indices) {
            outPixels[i] = if ((maskPixels[i] ushr 24) > 8) {
                (pixels[i] and 0x00FFFFFF) or (0xFF shl 24)
            } else {
                0
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    /**
     * One step of the eikonal solver: how long the front takes to reach a pixel,
     * given its two already-reached neighbours along each axis.
     */
    private fun solve(
        flag: ByteArray,
        time: FloatArray,
        w: Int,
        h: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int
    ): Float {
        val first = x1 >= 0 && y1 >= 0 && x1 < w && y1 < h && flag[y1 * w + x1] != INSIDE
        val second = x2 >= 0 && y2 >= 0 && x2 < w && y2 < h && flag[y2 * w + x2] != INSIDE
        if (!first && !second) return INF
        if (!second) return 1f + time[y1 * w + x1]
        if (!first) return 1f + time[y2 * w + x2]
        val t1 = time[y1 * w + x1]
        val t2 = time[y2 * w + x2]
        val discriminant = 2f - (t1 - t2) * (t1 - t2)
        if (discriminant > 0f) {
            val root = sqrt(discriminant)
            var solution = (t1 + t2 - root) / 2f
            if (solution >= t1 && solution >= t2) return solution
            solution = (t1 + t2 + root) / 2f
            if (solution >= t1 && solution >= t2) return solution
        }
        return 1f + min(t1, t2)
    }

    /**
     * Estimate a pixel's value from the known pixels around it, using Telea's
     * `dir` / `dst` / `lev` weights. Each neighbour also contributes its own
     * gradient, which keeps a ramp a ramp instead of flattening it.
     */
    private fun estimate(
        pixels: IntArray,
        flag: ByteArray,
        time: FloatArray,
        w: Int,
        h: Int,
        px: Int,
        py: Int
    ): Int {
        val center = py * w + px
        var nx = 0f
        var ny = 0f
        if (px + 1 < w && px - 1 >= 0) nx = (time[center + 1] - time[center - 1]) / 2f
        if (py + 1 < h && py - 1 >= 0) ny = (time[center + w] - time[center - w]) / 2f

        var accR = 0f
        var accG = 0f
        var accB = 0f
        var total = 0f
        // The neighbourhood window is fixed, so every distance-dependent weight
        // term is precomputed; the hot loop does no sqrt or division for them.
        for (offset in OFFSETS) {
            val qx = px + offset.dx
            if (qx < 0 || qx >= w) continue
            val qy = py + offset.dy
            if (qy < 0 || qy >= h) continue
            val q = qy * w + qx
            if (flag[q] == INSIDE) continue
            val dir = abs(offset.rxNorm * nx + offset.ryNorm * ny) + 1e-6f
            val lev = 1f / (1f + abs(time[q] - time[center]))
            val weight = dir * offset.invDistSq * lev

            val qColor = pixels[q]
            var valueR = (qColor shr 16 and 0xFF).toFloat()
            var valueG = (qColor shr 8 and 0xFF).toFloat()
            var valueB = (qColor and 0xFF).toFloat()
            if (qx + 1 < w && qx - 1 >= 0) {
                if (flag[q + 1] != INSIDE && flag[q - 1] != INSIDE) {
                    val right = pixels[q + 1]
                    val left = pixels[q - 1]
                    valueR += ((right shr 16 and 0xFF) - (left shr 16 and 0xFF)) / 2f * offset.rx
                    valueG += ((right shr 8 and 0xFF) - (left shr 8 and 0xFF)) / 2f * offset.rx
                    valueB += ((right and 0xFF) - (left and 0xFF)) / 2f * offset.rx
                }
            }
            if (qy + 1 < h && qy - 1 >= 0) {
                if (flag[q + w] != INSIDE && flag[q - w] != INSIDE) {
                    val down = pixels[q + w]
                    val up = pixels[q - w]
                    valueR += ((down shr 16 and 0xFF) - (up shr 16 and 0xFF)) / 2f * offset.ry
                    valueG += ((down shr 8 and 0xFF) - (up shr 8 and 0xFF)) / 2f * offset.ry
                    valueB += ((down and 0xFF) - (up and 0xFF)) / 2f * offset.ry
                }
            }
            accR += weight * valueR
            accG += weight * valueG
            accB += weight * valueB
            total += weight
        }
        if (total == 0f) return 0xFF shl 24
        val r = (accR / total).toInt().coerceIn(0, 255)
        val g = (accG / total).toInt().coerceIn(0, 255)
        val b = (accB / total).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * A binary min-heap keyed on arrival time — the fast marching front.
     * Parallel primitive arrays, so marching allocates nothing per pixel.
     */
    private class Front {
        private var times = FloatArray(256)
        private var xs = IntArray(256)
        private var ys = IntArray(256)
        private var size = 0

        var popX = 0
            private set
        var popY = 0
            private set

        val isNotEmpty: Boolean get() = size > 0

        fun push(t: Float, x: Int, y: Int) {
            if (size == times.size) {
                times = times.copyOf(size * 2)
                xs = xs.copyOf(size * 2)
                ys = ys.copyOf(size * 2)
            }
            var index = size
            times[index] = t
            xs[index] = x
            ys[index] = y
            size++
            while (index > 0) {
                val parent = (index - 1) shr 1
                if (times[parent] <= times[index]) break
                swap(parent, index)
                index = parent
            }
        }

        fun pop() {
            popX = xs[0]
            popY = ys[0]
            size--
            if (size > 0) {
                times[0] = times[size]
                xs[0] = xs[size]
                ys[0] = ys[size]
                var index = 0
                while (true) {
                    val left = index * 2 + 1
                    val right = left + 1
                    var smallest = index
                    if (left < size && times[left] < times[smallest]) smallest = left
                    if (right < size && times[right] < times[smallest]) smallest = right
                    if (smallest == index) break
                    swap(smallest, index)
                    index = smallest
                }
            }
        }

        private fun swap(a: Int, b: Int) {
            val t = times[a]; times[a] = times[b]; times[b] = t
            val x = xs[a]; xs[a] = xs[b]; xs[b] = x
            val y = ys[a]; ys[a] = ys[b]; ys[b] = y
        }
    }
}
