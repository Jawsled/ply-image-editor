package com.ply.editor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM checks for the array-level core of [TeleaInpainter]. The marching fill is
 * pure integer/float array code, so it runs on the JVM — and these tests exist
 * because a porting bug once made the front never advance, which turned the
 * fill into an endless loop that only showed up on device.
 */
class TeleaInpainterTest {

    private fun gradientImage(w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x * 255 / (w - 1).coerceAtLeast(1)
                val g = y * 255 / (h - 1).coerceAtLeast(1)
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or 128
            }
        }
        return pixels
    }

    private fun holeMask(w: Int, h: Int, x0: Int, x1: Int, y0: Int, y1: Int): IntArray {
        val mask = IntArray(w * h)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                mask[y * w + x] = 0xFFFF3B30.toInt()
            }
        }
        return mask
    }

    @Test
    fun `fill terminates and replaces the hole`() {
        val w = 128
        val h = 96
        val src = gradientImage(w, h)
        val mask = holeMask(w, h, 40, 88, 30, 66)
        // Put an "object" under the mask: a white block the fill must erase. On a
        // bare gradient, correct Telea output reproduces the ramp (mean diff ~0),
        // which is the algorithm working as designed, not a failure.
        for (y in 30 until 66) {
            for (x in 40 until 88) {
                src[y * w + x] = 0xFFFFFFFF.toInt()
            }
        }
        val before = src.clone()

        val start = System.nanoTime()
        TeleaInpainter.fill(src, mask, w, h)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // A 48x36 hole on a 128x96 gradient must complete in well under a second
        // on the JVM; the old never-advancing-front bug hung here forever.
        assertTrue("fill took ${elapsedMs}ms", elapsedMs < 2_000)

        var holePixels = 0
        var holeDiff = 0L
        var knownChanged = 0
        for (i in mask.indices) {
            if ((mask[i] ushr 24) > 8) {
                holePixels++
                for (c in 0..2) {
                    holeDiff += abs(
                        ((src[i] shr (16 - 8 * c)) and 0xFF) -
                            ((before[i] shr (16 - 8 * c)) and 0xFF)
                    )
                }
            } else if (src[i] != before[i]) {
                knownChanged++
            }
        }
        assertTrue("hole was not filled (mean diff $holeDiff)", holePixels > 0 && holeDiff / holePixels > 50)
        assertEquals("known pixels must be untouched", 0, knownChanged)
    }

    @Test
    fun `downsample marks every coarse cell containing a hole`() {
        val w = 32
        val h = 32
        val src = gradientImage(w, h)
        val mask = holeMask(w, h, 8, 10, 8, 10) // 2x2 hole -> exactly one coarse cell
        val (low, lowMask) = TeleaInpainter.downsample(src, mask, w, h, 16, 16)

        var coarseHoles = 0
        for (v in lowMask) if ((v ushr 24) > 8) coarseHoles++
        assertEquals(1, coarseHoles)
    }

    @Test
    fun `full-hole input is rejected by the caller contract`() {
        // inpaint() bails before filling when every pixel is masked; fill itself
        // must also refuse to march on an all-hole grid.
        val w = 8
        val h = 8
        val src = gradientImage(w, h)
        val mask = IntArray(w * h) { 0xFFFF3B30.toInt() }
        val before = src.clone()
        TeleaInpainter.fill(src, mask, w, h)
        assertEquals(before.toList(), src.toList())
    }
}
