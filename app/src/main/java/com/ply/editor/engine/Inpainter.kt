package com.ply.editor.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.ply.editor.engine.onnx.InpaintModelId
import com.ply.editor.engine.onnx.LamaInpainter
import com.ply.editor.model.Document
import com.ply.editor.model.Layer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Object removal orchestrator: renders a bounded crop around the painted mask,
 * hands it to the selected engine, and composites the patch back through the
 * inverse layer matrix so position/scale/rotation survive.
 *
 * Engines live behind [InpaintModelId]:
 * - [InpaintModelId.CLASSIC] — the original no-neural-net BFS nearest-color fill,
 *   instant on any device. See [BfsInpainter].
 * - [InpaintModelId.TELEA] — the edge-aware Telea fast-marching fill, also
 *   neural-net-free. See [TeleaInpainter].
 * - [InpaintModelId.MIGAN] — the bundled MI-GAN pipeline (`.ort` asset, uint8
 *   tensors, works offline). See [runMigan].
 * - [InpaintModelId.LAMA] — the ImageToolbox-style dynamic LaMa pipeline
 *   (downloaded `.onnx`, float tensors, tiled on large regions). See
 *   [LamaInpainter].
 *
 * The generated pixels are written back into the *active layer only*.
 */
object Inpainter {

    private const val TAG = "PlyInpainter"
    private const val MODEL_ASSET = "migan_pipeline_v2.ort"
    private const val MODEL_FILE = "migan_pipeline_v2.ort"

    /**
     * Longest edge of the crop handed to each engine. The classic fills and the
     * MI-GAN generator need no more than this; the LaMa pipeline tiles large
     * regions itself and benefits from extra resolution.
     */
    private const val CLASSIC_WORK_EDGE = 1024f
    private const val TELEA_WORK_EDGE = 1024f
    private const val MIGAN_WORK_EDGE = 1280f
    private const val LAMA_WORK_EDGE = 2560f

    /** Padding around the painted mask, as a fraction of the mask's longest edge. */
    private const val CONTEXT_FRACTION = 0.35f
    private const val MIN_CONTEXT_PX = 48f

    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private var miganSession: OrtSession? = null
    private var loadError: String? = null

    val error: String? get() = loadError

    fun releaseSessions() {
        synchronized(lock) {
            miganSession?.close()
            miganSession = null
        }
        LamaInpainter.releaseSession()
    }

    /**
     * Remove the painted area from [layer] and return a new bitmap with the same size.
     *
     * @param mask bitmap whose alpha marks the object; its coordinates span the whole document.
     * @param maskScale mask pixels per document pixel (mask.width / doc.width).
     */
    fun inpaintLayer(
        context: Context,
        doc: Document,
        layer: Layer,
        mask: Bitmap,
        maskScale: Float,
        model: InpaintModelId
    ): Bitmap? {
        if (maskScale <= 0f) return null
        val bounds = maskBounds(mask) ?: return null

        val maxWorkEdge = when (model) {
            InpaintModelId.CLASSIC -> CLASSIC_WORK_EDGE
            InpaintModelId.TELEA -> TELEA_WORK_EDGE
            InpaintModelId.MIGAN -> MIGAN_WORK_EDGE
            InpaintModelId.LAMA -> LAMA_WORK_EDGE
        }

        val maskW = bounds.width() / maskScale
        val maskH = bounds.height() / maskScale
        val padding = max(MIN_CONTEXT_PX, max(maskW, maskH) * CONTEXT_FRACTION)

        val left = floor(max(0f, bounds.left / maskScale - padding)).toInt()
        val top = floor(max(0f, bounds.top / maskScale - padding)).toInt()
        val right = ceil(min(doc.width.toFloat(), bounds.right / maskScale + padding)).toInt()
        val bottom = ceil(min(doc.height.toFloat(), bounds.bottom / maskScale + padding)).toInt()
        val cropW = right - left
        val cropH = bottom - top
        if (cropW < 2 || cropH < 2) return null

        val renderScale = min(1f, maxWorkEdge / max(cropW, cropH).toFloat())
        val workW = (cropW * renderScale).roundToInt().coerceAtLeast(2)
        val workH = (cropH * renderScale).roundToInt().coerceAtLeast(2)

        val source = renderSource(doc, layer, left, top, renderScale, workW, workH) ?: return null
        val workMask = renderMask(mask, maskScale, left, top, renderScale, workW, workH)
        if (workMask == null) {
            source.recycle()
            return null
        }

        val patch = try {
            when (model) {
                InpaintModelId.CLASSIC -> BfsInpainter.inpaint(source, workMask)
                InpaintModelId.TELEA -> TeleaInpainter.inpaint(source, workMask)
                InpaintModelId.MIGAN -> {
                    val generated = runMigan(context, source, workMask, workW, workH)
                    if (generated == null) null else {
                        val stripped = maskedPatch(generated, workMask)
                        generated.recycle()
                        stripped
                    }
                }
                InpaintModelId.LAMA -> LamaInpainter.inpaint(context, source, workMask)
            }
        } finally {
            source.recycle()
            workMask.recycle()
        }
        if (patch == null) return null

        val work = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (work == null) {
            patch.recycle()
            return null
        }
        val inverse = Matrix()
        if (!layer.matrix().invert(inverse)) {
            work.recycle()
            patch.recycle()
            return null
        }
        val canvas = Canvas(work)
        canvas.save()
        canvas.concat(inverse)
        canvas.translate(left.toFloat(), top.toFloat())
        canvas.scale(1f / renderScale, 1f / renderScale)
        canvas.drawBitmap(
            patch,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        canvas.restore()
        patch.recycle()
        return work
    }

    /** Render the active layer in document space over the requested crop. */
    private fun renderSource(
        doc: Document,
        layer: Layer,
        left: Int,
        top: Int,
        renderScale: Float,
        width: Int,
        height: Int
    ): Bitmap? {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (Color.alpha(doc.background) > 0) canvas.drawColor(doc.background)
        canvas.scale(renderScale, renderScale)
        canvas.translate(-left.toFloat(), -top.toFloat())
        canvas.concat(layer.matrix())
        canvas.drawBitmap(
            layer.bitmap,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    /** Draw the document-space mask into the crop, scaled to the working size. */
    private fun renderMask(
        mask: Bitmap,
        maskScale: Float,
        left: Int,
        top: Int,
        renderScale: Float,
        width: Int,
        height: Int
    ): Bitmap? {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.scale(renderScale, renderScale)
        canvas.translate(-left.toFloat(), -top.toFloat())
        canvas.scale(1f / maskScale, 1f / maskScale)
        canvas.drawBitmap(mask, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Keep only the generated pixels inside the mask; everything else becomes transparent. */
    private fun maskedPatch(generated: Bitmap, mask: Bitmap): Bitmap {
        val w = generated.width
        val h = generated.height
        val colors = IntArray(w * h)
        val maskPixels = IntArray(w * h)
        generated.getPixels(colors, 0, w, 0, 0, w, h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        for (i in colors.indices) {
            colors[i] = if ((maskPixels[i] ushr 24) > 8) {
                (colors[i] and 0x00FFFFFF) or (0xFF shl 24)
            } else {
                0
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(colors, 0, w, 0, 0, w, h)
        return out
    }

    // ------------------------------------------------------------------ MI-GAN

    /**
     * The shipped MI-GAN pipeline (`migan_pipeline_v2.ort`) accepts an RGB crop and
     * a binary mask as uint8 tensors (mask: 255 = known, 0 = hole) and returns the
     * inpainted RGB crop.
     */
    private fun runMigan(
        context: Context,
        source: Bitmap,
        mask: Bitmap,
        width: Int,
        height: Int
    ): Bitmap? {
        val sess = ensureMiganSession(context) ?: return null
        synchronized(lock) {
            val env = environment ?: return null
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            val plane = width * height
            val imageBuffer = ByteBuffer.allocateDirect(plane * 3)
            for (i in pixels.indices) {
                val color = pixels[i]
                imageBuffer.put(i, (color shr 16 and 0xFF).toByte())
                imageBuffer.put(plane + i, (color shr 8 and 0xFF).toByte())
                imageBuffer.put(plane * 2 + i, (color and 0xFF).toByte())
            }

            val maskPixels = IntArray(plane)
            mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
            val maskBuffer = ByteBuffer.allocateDirect(plane)
            for (i in maskPixels.indices) {
                // The pipeline expects 255 = known, 0 = hole.
                maskBuffer.put(
                    i,
                    if ((maskPixels[i] ushr 24) > 8) 0 else 255.toByte()
                )
            }

            return try {
                OnnxTensor.createTensor(
                    env, imageBuffer, longArrayOf(1, 3, height.toLong(), width.toLong()),
                    OnnxJavaType.UINT8
                ).use { imageTensor ->
                    OnnxTensor.createTensor(
                        env, maskBuffer, longArrayOf(1, 1, height.toLong(), width.toLong()),
                        OnnxJavaType.UINT8
                    ).use { maskTensor ->
                        sess.run(mapOf("image" to imageTensor, "mask" to maskTensor)).use { result ->
                            val output = result.get(0) as? OnnxTensor ?: return null
                            val buffer = output.byteBuffer
                            if (buffer.remaining() < plane * 3) return null
                            val out = IntArray(plane)
                            for (i in 0 until plane) {
                                val r = buffer.get(i).toInt() and 0xFF
                                val g = buffer.get(plane + i).toInt() and 0xFF
                                val b = buffer.get(plane * 2 + i).toInt() and 0xFF
                                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.setPixels(out, 0, width, 0, 0, width, height)
                            bitmap
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MI-GAN inference failed", t)
                null
            }
        }
    }

    private fun ensureMiganSession(context: Context): OrtSession? {
        synchronized(lock) {
            miganSession?.let { return it }
            loadError?.let { return null }
            return try {
                val env = OrtEnvironment.getEnvironment()
                val file = miganModelFile(context)
                val options = OrtSession.SessionOptions()
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(
                    Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                )
                val created = env.createSession(file.absolutePath, options)
                options.close()
                environment = env
                miganSession = created
                created
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Could not load MI-GAN model", t)
                null
            }
        }
    }

    private fun miganModelFile(context: Context): File {
        val file = File(context.filesDir, MODEL_FILE)
        if (file.exists() && file.length() > 1_000_000L) return file
        val temp = File(context.filesDir, "$MODEL_FILE.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            if (!file.exists()) error("Could not install the object-removal model")
        }
        return file
    }

    /** Bounding box of painted mask pixels, in mask coordinates. */
    private fun maskBounds(mask: Bitmap): Rect? {
        val w = mask.width
        val h = mask.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }
}
