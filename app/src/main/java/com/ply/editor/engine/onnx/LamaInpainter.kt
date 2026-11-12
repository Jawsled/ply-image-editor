package com.ply.editor.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Dynamic-resolution LaMa inpainting, ported from ImageToolbox's
 * `lib/neural-tools/inpaint/LaMaProcessor.kt` (Apache-2.0).
 *
 * The graph (`LaMa_dynamic.onnx`, published by the ImageToolbox project) takes a
 * normalized float image `image` [1,3,H,W] and a binary float `mask` [1,1,H,W]
 * (1 = hole), both aligned to a multiple of 8 and reflected at the edges, and
 * returns RGB floats in the 0..255 range. Two outputs exist: `output` (single-pass
 * quality) and `prediction` (used when tiling, so every tile shares the same
 * pipeline as the overview pass).
 *
 * Execution strategy, identical to ImageToolbox:
 * - The mask bounds are expanded by `max(256 px, mask size)` of context.
 * - A context region of at most 1024 px on the long edge is inpainted in one pass.
 * - Larger regions run an overview pass at ≤1024 px first, then the mask is
 *   processed in 768 px tiles (128 px context padding per tile). Each tile blends
 *   its prediction with the upscaled overview (`overview + patch − low-pass patch`)
 *   so large removals stay consistent.
 *
 * The result obeys Ply's engine contract: masked pixels carry the generated color
 * with full alpha, everything else is transparent, so the caller can composite the
 * patch back through the inverse layer matrix.
 */
object LamaInpainter {

    private const val TAG = "PlyLama"

    /** The exact file the ImageToolbox project distributes for this pipeline. */
    const val MODEL_URL =
        "https://huggingface.co/T8RIN/imagetoolbox-models/resolve/main/onnx/inpaint/lama/LaMa_dynamic.onnx"
    const val MODEL_SIZE_BYTES = 205_294_245L
    const val MODEL_SHA256 = "a1a100b4302c9c3e68bac108a6b7d4ba640061c41d8aeed7f28daea067cd71c8"
    const val MODEL_FILE_NAME = "LaMa_dynamic.onnx"

    private const val MODEL_ALIGNMENT = 8
    private const val CONTEXT_PADDING = 256
    private const val TILE_SIZE = 768
    private const val TILE_PADDING = 128
    private const val OVERVIEW_SIZE = 1024
    private const val DEFAULT_THREADS = 4

    private val lock = Any()
    private var session: OrtSession? = null
    private var loadError: String? = null
    private var runError: String? = null

    /** Why the last LaMa attempt failed, if it did (load or inference). */
    val error: String?
        get() = synchronized(lock) { loadError ?: runError }

    private fun directory(context: Context): File =
        File(context.filesDir, "onnx").apply { mkdirs() }

    fun modelFile(context: Context): File = File(directory(context), MODEL_FILE_NAME)

    fun isDownloaded(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() > 0L
    }

    /** Drop the cached session, e.g. after the model file changed on disk. */
    fun releaseSession() {
        synchronized(lock) {
            session?.close()
            session = null
            loadError = null
            runError = null
        }
    }

    /**
     * Fill the painted area of [mask] inside [image]. Both bitmaps must have the
     * same size. Returns the image with masked pixels replaced (opaque) and
     * unmasked pixels transparent, or null when the model cannot run.
     */
    fun inpaint(context: Context, image: Bitmap, mask: Bitmap): Bitmap? = try {
        synchronized(lock) { runError = null }
        val fullMask = if (mask.width != image.width || mask.height != image.height) {
            Bitmap.createScaledBitmap(mask, image.width, image.height, false)
        } else {
            mask
        }
        try {
            val selection = maskBounds(fullMask) ?: return Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            val bounds = selection.bounds
            val region = bounds.expanded(
                maxOf(CONTEXT_PADDING, bounds.width(), bounds.height()),
                image
            )
            if (maxOf(region.width(), region.height()) > OVERVIEW_SIZE) {
                inpaintTiled(context, image, fullMask, selection, region)
            } else {
                val predicted = inpaintRegion(
                    context,
                    image,
                    fullMask,
                    region,
                    region.width(),
                    region.height(),
                    OUTPUT_NAME
                )
                val patch = if (predicted.width != region.width() || predicted.height != region.height()) {
                    Bitmap.createScaledBitmap(predicted, region.width(), region.height(), true)
                        .also { if (it !== predicted) predicted.recycle() }
                } else {
                    predicted
                }
                try {
                    composite(image, fullMask, patch, region)
                } finally {
                    patch.recycle()
                }
            }
        } finally {
            if (fullMask !== mask) fullMask.recycle()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "LaMa inpainting failed", t)
        synchronized(lock) { runError = t.message ?: t.javaClass.simpleName }
        null
    }

    // ----------------------------------------------------------------- region

    private class MaskBounds(val bounds: Rect, val pixels: Int)

    /** Bounding box of painted pixels (red channel set on opaque pixels). */
    private fun maskBounds(mask: Bitmap, region: Rect = Rect(0, 0, mask.width, mask.height)): MaskBounds? {
        val row = IntArray(region.width())
        val bounds = Rect(region.right, region.bottom, region.left, region.top)
        var pixels = 0
        for (y in region.top until region.bottom) {
            mask.getPixels(row, 0, row.size, region.left, y, row.size, 1)
            row.forEachIndexed { index, color ->
                if (Color.red(color) > 0 && Color.alpha(color) > 0) {
                    val x = region.left + index
                    bounds.left = minOf(bounds.left, x)
                    bounds.top = minOf(bounds.top, y)
                    bounds.right = maxOf(bounds.right, x + 1)
                    bounds.bottom = maxOf(bounds.bottom, y + 1)
                    pixels++
                }
            }
        }
        return bounds.takeUnless(Rect::isEmpty)?.let { MaskBounds(it, pixels) }
    }

    private fun Rect.expanded(padding: Int, image: Bitmap): Rect = Rect(
        (left - padding).coerceAtLeast(0),
        (top - padding).coerceAtLeast(0),
        (right + padding).coerceAtMost(image.width),
        (bottom + padding).coerceAtMost(image.height)
    )

    private fun inpaintRegion(
        context: Context,
        image: Bitmap,
        mask: Bitmap,
        region: Rect,
        width: Int,
        height: Int,
        outputName: String
    ): Bitmap {
        val sess = ensureSession(context) ?: error("LaMa session unavailable")
        val scaled = width != region.width() || height != region.height()
        val inputImage = if (scaled) scaleRegion(image, region, width, height, false) else image
        val inputMask = if (scaled) scaleRegion(mask, region, width, height, true) else mask
        val inputRegion = if (scaled) Rect(0, 0, width, height) else region
        val inputWidth = width.alignedSize()
        val inputHeight = height.alignedSize()
        try {
            bitmapToTensor(inputImage, inputRegion, inputWidth, inputHeight, false).use { tensorImage ->
                bitmapToTensor(inputMask, inputRegion, inputWidth, inputHeight, true).use { tensorMask ->
                    sess.run(
                        mapOf("image" to tensorImage, "mask" to tensorMask),
                        setOf(outputName)
                    ).use { result ->
                        // ORT 1.20+ changed Result.get(String) to Optional<OnnxValue>.
                        val output = result.get(outputName).orElse(null) as? OnnxTensor
                            ?: error("LaMa output '$outputName' missing")
                        return outputBufferToBitmap(
                            output.floatBuffer,
                            width,
                            height,
                            inputWidth,
                            inputHeight
                        )
                    }
                }
            }
        } finally {
            if (scaled) {
                inputImage.recycle()
                inputMask.recycle()
            }
        }
    }

    private fun inpaintTiled(
        context: Context,
        image: Bitmap,
        mask: Bitmap,
        selection: MaskBounds,
        region: Rect
    ): Bitmap {
        val scale = minOf(1f, OVERVIEW_SIZE.toFloat() / maxOf(region.width(), region.height()))
        val overview = if (selection.pixels > 256 * 256) {
            val prediction = inpaintRegion(
                context,
                image,
                mask,
                region,
                (region.width() * scale).roundToInt().coerceAtLeast(1),
                (region.height() * scale).roundToInt().coerceAtLeast(1),
                PREDICTION_NAME
            )
            Bitmap.createScaledBitmap(
                prediction,
                region.width(),
                region.height(),
                true
            ).also { if (it !== prediction) prediction.recycle() }
        } else {
            null
        }
        val result = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val bounds = selection.bounds
            for (y in bounds.top until bounds.bottom step TILE_SIZE) {
                for (x in bounds.left until bounds.right step TILE_SIZE) {
                    val core = Rect(
                        x,
                        y,
                        minOf(x + TILE_SIZE, bounds.right),
                        minOf(y + TILE_SIZE, bounds.bottom)
                    )
                    val tileMask = maskBounds(mask, core) ?: continue
                    val tile = tileMask.bounds.expanded(
                        if (maxOf(
                                tileMask.bounds.width(),
                                tileMask.bounds.height()
                            ) < 256
                        ) CONTEXT_PADDING else TILE_PADDING,
                        image
                    )
                    val prediction = inpaintRegion(
                        context,
                        image,
                        mask,
                        tile,
                        tile.width(),
                        tile.height(),
                        PREDICTION_NAME
                    )
                    val low = if (overview != null) {
                        val small = Bitmap.createScaledBitmap(
                            prediction,
                            (prediction.width * scale).roundToInt().coerceAtLeast(1),
                            (prediction.height * scale).roundToInt().coerceAtLeast(1),
                            true
                        )
                        Bitmap.createScaledBitmap(small, prediction.width, prediction.height, true)
                            .also { small.recycle() }
                    } else {
                        null
                    }
                    try {
                        compositeTile(result, mask, prediction, tileMask.bounds, tile, overview, region, low)
                    } finally {
                        low?.recycle()
                        prediction.recycle()
                    }
                }
            }
            return result
        } catch (throwable: Throwable) {
            result.recycle()
            throw throwable
        } finally {
            overview?.recycle()
        }
    }

    private fun compositeTile(
        result: Bitmap,
        mask: Bitmap,
        prediction: Bitmap,
        core: Rect,
        tile: Rect,
        overview: Bitmap?,
        region: Rect,
        low: Bitmap?
    ) {
        val original = IntArray(core.width())
        val maskRow = IntArray(core.width())
        val patch = IntArray(core.width())
        val guide = IntArray(core.width())
        val lowRow = IntArray(core.width())
        for (y in core.top until core.bottom) {
            result.getPixels(original, 0, original.size, core.left, y, original.size, 1)
            mask.getPixels(maskRow, 0, maskRow.size, core.left, y, maskRow.size, 1)
            prediction.getPixels(
                patch,
                0,
                patch.size,
                core.left - tile.left,
                y - tile.top,
                patch.size,
                1
            )
            overview?.getPixels(
                guide,
                0,
                guide.size,
                core.left - region.left,
                y - region.top,
                guide.size,
                1
            )
            low?.getPixels(lowRow, 0, lowRow.size, core.left - tile.left, y - tile.top, lowRow.size, 1)
            for (x in original.indices) {
                if (Color.red(maskRow[x]) > 0 && Color.alpha(maskRow[x]) > 0) {
                    val color = if (overview != null && low != null) {
                        Color.rgb(
                            (Color.red(guide[x]) + Color.red(patch[x]) - Color.red(lowRow[x])).coerceIn(0, 255),
                            (Color.green(guide[x]) + Color.green(patch[x]) - Color.green(lowRow[x])).coerceIn(0, 255),
                            (Color.blue(guide[x]) + Color.blue(patch[x]) - Color.blue(lowRow[x])).coerceIn(0, 255)
                        )
                    } else {
                        patch[x]
                    }
                    // The patch buffer only lives where the mask is painted; every other
                    // pixel stays transparent so the caller keeps the original artwork.
                    original[x] = (color and 0x00FFFFFF) or (0xFF shl 24)
                }
            }
            result.setPixels(original, 0, original.size, core.left, y, original.size, 1)
        }
    }

    private fun composite(source: Bitmap, mask: Bitmap, patch: Bitmap, region: Rect): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val maskRow = IntArray(region.width())
        val patchRow = IntArray(region.width())
        val out = IntArray(region.width())
        for (y in 0 until region.height()) {
            mask.getPixels(maskRow, 0, region.width(), region.left, region.top + y, region.width(), 1)
            patch.getPixels(patchRow, 0, region.width(), 0, y, region.width(), 1)
            for (x in maskRow.indices) {
                out[x] = if (Color.red(maskRow[x]) > 0 && Color.alpha(maskRow[x]) > 0) {
                    (patchRow[x] and 0x00FFFFFF) or (0xFF shl 24)
                } else {
                    0
                }
            }
            result.setPixels(out, 0, region.width(), region.left, region.top + y, region.width(), 1)
        }
        return result
    }

    private fun scaleRegion(
        source: Bitmap,
        region: Rect,
        width: Int,
        height: Int,
        isMask: Boolean
    ): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            source,
            region,
            Rect(0, 0, width, height),
            Paint().apply { isFilterBitmap = !isMask }
        )
        return out
    }

    // -------------------------------------------------------------- tensor i/o

    private fun Int.alignedSize(): Int =
        ((this + MODEL_ALIGNMENT - 1) / MODEL_ALIGNMENT * MODEL_ALIGNMENT).coerceAtLeast(32)

    private fun reflect(coordinate: Int, size: Int): Int {
        if (size == 1) return 0
        val position = coordinate % (2 * (size - 1))
        return if (position < size) position else 2 * (size - 1) - position
    }

    private fun floatBuffer(size: Int): FloatBuffer = ByteBuffer
        .allocateDirect(size * java.lang.Float.BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private fun bitmapToTensor(
        bitmap: Bitmap,
        region: Rect,
        width: Int,
        height: Int,
        isMask: Boolean
    ): OnnxTensor {
        val channels = if (isMask) 1 else 3
        val channelSize = width * height
        val data = floatBuffer(channels * channelSize)
        val pixels = IntArray(region.width())
        val row = FloatArray(width)
        for (y in 0 until height) {
            if (isMask && y >= region.height()) continue
            bitmap.getPixels(
                pixels,
                0,
                pixels.size,
                region.left,
                region.top + reflect(y, region.height()),
                pixels.size,
                1
            )
            for (channel in 0 until channels) {
                for (x in 0 until width) {
                    val color = pixels[reflect(x, pixels.size)]
                    row[x] = if (isMask) {
                        if (x < pixels.size && Color.red(color) > 0 && Color.alpha(color) > 0) 1f else 0f
                    } else {
                        ((color shr (16 - channel * 8)) and 0xFF) / 255f
                    }
                }
                data.position(channel * channelSize + y * width)
                data.put(row)
            }
        }
        data.rewind()
        return OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            data,
            longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
        )
    }

    private fun outputBufferToBitmap(
        buffer: FloatBuffer,
        width: Int,
        height: Int,
        inputWidth: Int,
        inputHeight: Int
    ): Bitmap {
        val channelSize = inputWidth * inputHeight
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val red = FloatArray(width)
        val green = FloatArray(width)
        val blue = FloatArray(width)
        val pixels = IntArray(width)
        for (y in 0 until height) {
            buffer.position(y * inputWidth)
            buffer.get(red)
            buffer.position(channelSize + y * inputWidth)
            buffer.get(green)
            buffer.position(2 * channelSize + y * inputWidth)
            buffer.get(blue)
            for (x in pixels.indices) {
                pixels[x] = Color.rgb(
                    red[x].toInt().coerceIn(0, 255),
                    green[x].toInt().coerceIn(0, 255),
                    blue[x].toInt().coerceIn(0, 255)
                )
            }
            result.setPixels(pixels, 0, width, 0, y, width, 1)
        }
        return result
    }

    // ------------------------------------------------------------------ model

    private const val OUTPUT_NAME = "output"
    private const val PREDICTION_NAME = "prediction"

    private fun ensureSession(context: Context): OrtSession? {
        synchronized(lock) {
            session?.let { return it }
            if (loadError != null) return null
            val file = modelFile(context)
            if (!file.isFile || file.length() == 0L) {
                loadError = "LaMa model is not downloaded"
                return null
            }
            return try {
                val options = OrtSession.SessionOptions().apply {
                    // The emulator build ships MLAS with Kleidiai kernels that crash
                    // inside QEMU; desktop hardware is unaffected.
                    if (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
                        runCatching { addConfigEntry("mlas.disable_kleidiai", "1") }
                    }
                    runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
                    runCatching { setInterOpNumThreads(1) }
                    runCatching { setIntraOpNumThreads(DEFAULT_THREADS) }
                    runCatching { addConfigEntry("session.dynamic_block_base", "4") }
                    runCatching { setMemoryPatternOptimization(true) }
                }
                options.use {
                    OrtEnvironment.getEnvironment().createSession(file.absolutePath, it)
                }.also { created ->
                    session = created
                    Log.i(TAG, "LaMa session ready (${file.length()} bytes)")
                }
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Could not load LaMa model", t)
                null
            }
        }
    }
}
