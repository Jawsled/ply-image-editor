package com.ply.editor.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * On-device background removal, following the model zoo of
 * [rembg](https://github.com/danielgatis/rembg) (the pipeline reference) and the
 * ONNX plumbing of ImageToolbox's `GenericBackgroundRemover` (Apache-2.0).
 *
 * Every supported model is a salient-object segmentation network with the same
 * contract: an RGB image squished to the model's trained size, normalized to
 * 0..1 in CHW order, produces a `[1,1,H,W]` foreground-probability map. The map
 * is min-max stretched (as rembg's u2net post-process does), bilinearly scaled
 * back to the source size, and applied as the alpha channel of the source
 * pixels — a soft cutout rather than a hard threshold.
 *
 * Models:
 * - [BackgroundModelId.U2NET_P] — bundled `.onnx` asset (4.5 MB), works offline,
 *   320 px, instant. The light default.
 * - [BackgroundModelId.ISNET] — downloadable DIS/ISNet general-use model, 1024 px.
 * - [BackgroundModelId.BIREFNET] — downloadable BiRefNet (swin-tiny) model,
 *   1024 px, the highest-quality tier.
 *
 * Cutout applies to the pixels passed in — callers hand in the active layer's
 * bitmap, so existing transparency survives and the matte only re-weights what
 * is already there.
 */
object BackgroundRemover {

    private const val TAG = "PlyBgRemover"
    private const val MODEL_ASSET = "u2netp.onnx"

    fun modelFile(context: Context, model: BackgroundModelId): File =
        File(File(context.filesDir, "onnx").apply { mkdirs() }, model.fileName)

    fun isReady(context: Context, model: BackgroundModelId): Boolean = when (model) {
        BackgroundModelId.U2NET_P -> true
        else -> modelFile(context, model).isFile && modelFile(context, model).length() > 0
    }

    fun releaseSessions() {
        synchronized(lock) {
            activeSession?.close()
            activeSession = null
            activeModel = null
            loadError = null
        }
    }

    val error: String? get() = synchronized(lock) { loadError ?: runError }

    /**
     * Cut the subject out of [image]: returns a bitmap of the same size whose
     * alpha is the model's foreground matte. Returns null when the model cannot
     * run; [error] then holds the reason.
     */
    fun cutout(context: Context, image: Bitmap, model: BackgroundModelId): Bitmap? = try {
        synchronized(lock) { runError = null }
        if (!isReady(context, model)) error("${model.label} model is not downloaded")
        val file = when (model) {
            BackgroundModelId.U2NET_P -> extractBundled(context)
            else -> modelFile(context, model)
        }
        val session = ensureSession(context, model, file) ?: error("Session unavailable")
        val size = model.trainedSize

        // A fully transparent layer has nothing to segment; feeding it in would
        // only produce an arbitrary matte over black.
        val sourcePixels = IntArray(image.width * image.height)
        image.getPixels(sourcePixels, 0, image.width, 0, 0, image.width, image.height)
        var opaque = 0
        for (p in sourcePixels) {
            if (((p ushr 24) and 0xFF) > 8) opaque++
        }
        if (opaque == 0) error("The layer is empty")

        // Squish to the trained size — the models are trained on square inputs
        // and the matte is scaled back afterwards, so no letterboxing is needed.
        val scaled = if (image.width == size && image.height == size) {
            image
        } else {
            Bitmap.createScaledBitmap(image, size, size, true)
        }
        val inputTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            bitmapToFloatBuffer(scaled, size, size),
            longArrayOf(1, 3, size.toLong(), size.toLong())
        )
        val inputName = synchronized(lock) { session.inputNames.first() }
        val outputName = synchronized(lock) { session.outputNames.first() }
        val matte = inputTensor.use {
            session.run(mapOf(inputName to it), setOf(outputName)).use { result ->
                // ORT 1.20+ returns Optional<OnnxValue> from get(String).
                val output = result.get(outputName).orElse(null) as? OnnxTensor
                    ?: error("Matte output '$outputName' missing")
                readMatte(output, size)
            }
        }
        if (scaled !== image) scaled.recycle()

        val mask = matteToBitmap(matte, size)
        val upscaledMask = if (image.width == size && image.height == size) {
            mask
        } else {
            Bitmap.createScaledBitmap(mask, image.width, image.height, true).also {
                mask.recycle()
            }
        }
        try {
            applyMatte(sourcePixels, upscaledMask, image.width, image.height)
        } finally {
            if (upscaledMask !== mask) upscaledMask.recycle()
            mask.recycle()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Background removal failed", t)
        synchronized(lock) { runError = t.message ?: t.javaClass.simpleName }
        null
    }

    // ------------------------------------------------------------------ matte

    private fun readMatte(output: OnnxTensor, size: Int): FloatArray {
        val plane = size * size
        val buffer = output.floatBuffer
        require(buffer.remaining() >= plane) { "Unexpected matte size" }
        val matte = FloatArray(plane)
        for (i in 0 until plane) matte[i] = buffer.get(i)
        var min = 1f
        var max = 0f
        var sum = 0.0
        for (v in matte) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        val mean = (sum / matte.size).toFloat()
        Log.i(TAG, "matte: min=%.3f max=%.3f mean=%.3f".format(min, max, mean))
        val range = max - min
        // A flat map means the model saw nothing to segment (or miscomputed);
        // stretching it would only produce noise.
        if (range <= 1e-3f) {
            error("Segmentation returned a flat matte (range %.4f)".format(range))
        }
        // rembg's u2net post-process: stretch the matte across 0..1 so soft
        // predictions do not wash out into semi-transparent gray.
        if (min > 0f || max < 1f) {
            for (i in matte.indices) matte[i] = ((matte[i] - min) / range).coerceIn(0f, 1f)
        }
        return matte
    }

    private fun matteToBitmap(matte: FloatArray, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            val alpha = (matte[i] * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(alpha, 255, 255, 255)
        }
        out.setPixels(pixels, 0, size, 0, 0, size, size)
        return out
    }

    private fun applyMatte(
        sourcePixels: IntArray,
        mask: Bitmap,
        width: Int,
        height: Int
    ): Bitmap {
        val maskPixels = IntArray(width * height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val alpha = (maskPixels[i] ushr 24) and 0xFF
            pixels[i] = (alpha shl 24) or (sourcePixels[i] and 0x00FFFFFF)
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        result.setHasAlpha(true)
        return result
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, width: Int, height: Int): FloatBuffer {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = FloatBuffer.allocate(3 * width * height)
        var offsetR = 0
        var offsetG = width * height
        var offsetB = 2 * width * height
        for (p in pixels) {
            buffer.put(offsetR++, Color.red(p) / 255f)
            buffer.put(offsetG++, Color.green(p) / 255f)
            buffer.put(offsetB++, Color.blue(p) / 255f)
        }
        return buffer
    }

    // ------------------------------------------------------------------ model

    private val lock = Any()
    private var activeSession: OrtSession? = null
    private var activeModel: BackgroundModelId? = null
    private var loadError: String? = null
    private var runError: String? = null

    private fun extractBundled(context: Context): File {
        val file = modelFile(context, BackgroundModelId.U2NET_P)
        if (!file.isFile || file.length() < 1_000_000L) {
            val temp = File(context.filesDir, "onnx/u2netp.onnx.tmp")
            context.assets.open(MODEL_ASSET).use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (!temp.renameTo(file)) {
                temp.delete()
                if (!file.exists()) error("Could not install the background model")
            }
        }
        return file
    }

    private fun ensureSession(
        context: Context,
        model: BackgroundModelId,
        file: File
    ): OrtSession? {
        synchronized(lock) {
            if (activeSession != null && activeModel == model) return activeSession
            // Keep at most one segmentation session alive: the quality models
            // are large, and holding every visited model would crowd the heap.
            activeSession?.close()
            activeSession = null
            return try {
                val options = OrtSession.SessionOptions().apply {
                    // The emulator build ships MLAS with Kleidiai kernels that
                    // crash inside QEMU; desktop hardware is unaffected.
                    if (android.os.Build.HARDWARE == "ranchu" ||
                        android.os.Build.HARDWARE == "goldfish"
                    ) {
                        runCatching { addConfigEntry("mlas.disable_kleidiai", "1") }
                    }
                    runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
                    runCatching { setIntraOpNumThreads(4) }
                }
                options.use {
                    OrtEnvironment.getEnvironment().createSession(file.absolutePath, it)
                }.also { created ->
                    activeSession = created
                    activeModel = model
                    Log.i(TAG, "Background model ready: ${model.label}")
                }
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Could not load background model", t)
                null
            }
        }
    }
}
