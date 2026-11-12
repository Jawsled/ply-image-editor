package com.ply.editor.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MobileSAM point-to-mask selection, ported from ImageToolbox's
 * `bgremover/MobileSAM.kt` (Apache-2.0), itself an ONNX export of
 * [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) (the lightweight
 * SAM distilled for phones).
 *
 * Two stages:
 * 1. The image encoder runs once per bitmap (1024 px long edge, HWC 0..255
 *    floats) and produces an embedding tensor that stays alive in the session.
 * 2. The mask decoder runs per prompt — points (1 = include, 0 = exclude) or
 *    a box (corner labels 2/3) — and returns sigmoid logits at the output
 *    resolution.
 *
 * Ply uses it as a tap-to-select mask source for the Remove tool: each tap on
 * the layer adds an include-point and the decoder returns a mask that is
 * composited into the removal overlay; the chosen inpainting engine then fills
 * it. The decoder is small enough to run per tap; only the first tap pays the
 * encoder cost.
 */
object SmartSelector {

    const val ENCODER_FILE = "mobile_sam_image_encoder.onnx"
    const val DECODER_FILE = "sam_mask_decoder_single.onnx"
    const val ENCODER_BYTES = 28_157_093L
    const val DECODER_BYTES = 16_501_323L
    const val ENCODER_SHA256 = "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749"
    const val DECODER_SHA256 = "93915fc7c993ab9d59ab8c9ccd3bce37f7509c81ab4150a74abd4d2abbd8570d"

    private const val MODEL_DIR = "mobile_sam"
    private const val HOST = "https://huggingface.co/T8RIN/imagetoolbox-models/resolve/main/"

    /** The removal overlay's red, so the SAM mask matches brush-painted masks. */
    private const val MASK_COLOR = 0xFFFF3B30.toInt()

    /** Sigmoid cut for mask membership; slightly below 0.5 so the fuzzy
     *  boundary leans outward, matching the dilation margin. */
    private const val MASK_THRESHOLD = 0.45f

    /** Mask dilation as a fraction of the image's long edge (2%). */
    private const val MASK_MARGIN_FRACTION = 0.02f
    private const val MASK_MARGIN_MIN = 4
    private const val MASK_MARGIN_MAX = 24

    /**
     * Chebyshev dilation with a two-pass distance transform, O(n) regardless of
     * radius: inside stays true, and every pixel within [radius] of an inside
     * pixel becomes true. Two linear sweeps (top-left, bottom-right) propagate
     * the distance to the nearest inside pixel across the whole grid.
     */
    private fun dilate(inside: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return inside
        val INF = Int.MAX_VALUE / 4
        val distance = IntArray(inside.size) { if (inside[it]) 0 else INF }
        // Forward pass: inherit from top and left neighbors.
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                var d = distance[i]
                if (x > 0) d = min(d, distance[i - 1] + 1)
                if (y > 0) {
                    d = min(d, distance[i - width] + 1)
                    if (x > 0) d = min(d, distance[i - width - 1] + 1)
                    if (x < width - 1) d = min(d, distance[i - width + 1] + 1)
                }
                distance[i] = d
            }
        }
        // Backward pass: inherit from bottom and right neighbors.
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                val i = row + x
                var d = distance[i]
                if (x < width - 1) d = min(d, distance[i + 1] + 1)
                if (y < height - 1) {
                    d = min(d, distance[i + width] + 1)
                    if (x < width - 1) d = min(d, distance[i + width + 1] + 1)
                    if (x > 0) d = min(d, distance[i + width - 1] + 1)
                }
                distance[i] = d
            }
        }
        val out = BooleanArray(inside.size)
        for (i in out.indices) out[i] = distance[i] <= radius
        return out
    }

    fun url(fileName: String): String = "${HOST}$MODEL_DIR/$fileName"

    private fun directory(context: Context): File =
        File(File(context.filesDir, "onnx"), MODEL_DIR).apply { mkdirs() }

    fun modelFile(context: Context, fileName: String): File = File(directory(context), fileName)

    fun isReady(context: Context): Boolean =
        modelFile(context, ENCODER_FILE).let { it.isFile && it.length() == ENCODER_BYTES } &&
            modelFile(context, DECODER_FILE).let { it.isFile && it.length() == DECODER_BYTES }

    /** A tap prompt in normalized layer coordinates (0..1). */
    data class Prompt(val x: Float, val y: Float)

    private val lock = Any()
    private var activeSession: Session? = null
    private var sessionKey: Pair<Long, Int>? = null
    private var loadError: String? = null

    val error: String? get() = synchronized(lock) { loadError }

    fun release() {
        synchronized(lock) {
            activeSession?.close()
            activeSession = null
            sessionKey = null
        }
    }

    /**
     * Return the live session for [layerId]/[bitmap], creating (or recreating)
     * one when the layer bitmap changed. The encoder pass happens here.
     */
    fun sessionFor(
        context: Context,
        layerId: Long,
        bitmap: Bitmap
    ): Session? {
        val key = layerId to System.identityHashCode(bitmap)
        synchronized(lock) {
            if (activeSession != null && sessionKey == key) return activeSession
            activeSession?.close()
            activeSession = null
        }
        return try {
            val session = Session(context, bitmap)
            synchronized(lock) {
                activeSession?.close()
                activeSession = session
                sessionKey = key
                loadError = null
            }
            session
        } catch (t: Throwable) {
            Log.e("PlySmartSelect", "Encoder failed", t)
            synchronized(lock) { loadError = t.message ?: t.javaClass.simpleName }
            null
        }
    }

    /**
     * A live segmentation session over one bitmap. The bitmap must stay
     * unmodified for the session's lifetime; the caller holds [close] until
     * the layer changes.
     */
    class Session internal constructor(context: Context, image: Bitmap) : Closeable {

        private val environment = OrtEnvironment.getEnvironment()
        private val scaledWidth: Int
        private val scaledHeight: Int
        private val embeddings: OnnxTensor
        private val decoder: OrtSession

        init {
            val scale = 1024f / max(image.width, image.height)
            scaledWidth = (image.width * scale).roundToInt().coerceIn(1, 1024)
            scaledHeight = (image.height * scale).roundToInt().coerceIn(1, 1024)
            val resized = Bitmap.createScaledBitmap(image, scaledWidth, scaledHeight, true)
            val pixels = IntArray(scaledWidth * scaledHeight)
            resized.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
            if (resized !== image) resized.recycle()

            // Channel-last 0..255 floats, top-left anchored in a 1024×1024 plane.
            val buffer = FloatBuffer.allocate(1024 * 1024 * 3)
            for (y in 0 until scaledHeight) {
                for (x in 0 until scaledWidth) {
                    val pixel = pixels[y * scaledWidth + x]
                    val offset = (y * 1024 + x) * 3
                    buffer.put(offset, (pixel shr 16 and 255).toFloat())
                    buffer.put(offset + 1, (pixel shr 8 and 255).toFloat())
                    buffer.put(offset + 2, (pixel and 255).toFloat())
                }
            }

            val encoderFile = modelFile(context, ENCODER_FILE)
            val decoderFile = modelFile(context, DECODER_FILE)
            embeddings = options().use { options ->
                environment.createSession(encoderFile.absolutePath, options).use { encoder ->
                    OnnxTensor.createTensor(
                        environment,
                        buffer,
                        longArrayOf(1024, 1024, 3)
                    ).use { input ->
                        encoder.run(mapOf("input_image" to input)).use { result ->
                            val tensor = result[0] as OnnxTensor
                            val values = tensor.floatBuffer
                            val copy = FloatArray(values.remaining()).also(values::get)
                            OnnxTensor.createTensor(
                                environment,
                                FloatBuffer.wrap(copy),
                                tensor.info.shape
                            )
                        }
                    }
                }
            }
            decoder = options().use {
                environment.createSession(decoderFile.absolutePath, it)
            }
        }

        /**
         * Run the decoder for [prompts] (normalized 0..1 layer coordinates) and
         * return an [image.width]×[image.height] ARGB mask: red where the
         * sigmoid logit passes 0.5, transparent elsewhere.
         */
        fun segment(image: Bitmap, prompts: List<Prompt>): Bitmap {
            require(prompts.isNotEmpty()) { "Add a point first" }
            val width = image.width
            val height = image.height
            val count = prompts.size

            val coords = FloatArray(count * 2)
            val labels = FloatArray(count)
            prompts.forEachIndexed { index, prompt ->
                coords[index * 2] = prompt.x.coerceIn(0f, 1f) * scaledWidth
                coords[index * 2 + 1] = prompt.y.coerceIn(0f, 1f) * scaledHeight
                labels[index] = 1f
            }

            val tensors = mutableListOf<OnnxTensor>()
            try {
                tensors += OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(coords),
                    longArrayOf(1, count.toLong(), 2)
                )
                tensors += OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(labels),
                    longArrayOf(1, count.toLong())
                )
                tensors += OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.allocate(256 * 256),
                    longArrayOf(1, 1, 256, 256)
                )
                tensors += OnnxTensor.createTensor(environment, floatArrayOf(0f))
                tensors += OnnxTensor.createTensor(
                    environment,
                    floatArrayOf(height.toFloat(), width.toFloat())
                )
                val inputs = mapOf(
                    "point_coords" to tensors[0],
                    "point_labels" to tensors[1],
                    "mask_input" to tensors[2],
                    "has_mask_input" to tensors[3],
                    "orig_im_size" to tensors[4],
                    "image_embeddings" to embeddings
                )
                decoder.run(inputs).use { output ->
                    val tensor = output[0] as OnnxTensor
                    val values = tensor.floatBuffer
                    val plane = width * height
                    // The layer's own pixels all carry alpha (photos are fully
                    // opaque), so the mask must be built from the logits alone:
                    // inside = sigmoid > threshold, outside = transparent.
                    // Raising alpha in place would leave the untouched photo
                    // pixels opaque and mask the whole image.
                    val inside = BooleanArray(plane)
                    val threshold = MASK_THRESHOLD
                    for (i in 0 until min(plane, values.remaining())) {
                        val logit = values.get(i).coerceIn(-20f, 20f)
                        val opacity = 1f / (1f + exp(-logit))
                        inside[i] = opacity > threshold
                    }
                    // Inpainting wants the mask slightly larger than the object:
                    // the margin gives the generator context and hides seam
                    // artifacts at the boundary. ~2% of the long edge, clamped.
                    val radius = (max(width, height) * MASK_MARGIN_FRACTION)
                        .roundToInt()
                        .coerceIn(MASK_MARGIN_MIN, MASK_MARGIN_MAX)
                    val dilated = dilate(inside, width, height, radius)
                    val pixels = IntArray(plane)
                    for (i in pixels.indices) {
                        pixels[i] = if (dilated[i]) MASK_COLOR else 0
                    }
                    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        setPixels(pixels, 0, width, 0, 0, width, height)
                        setHasAlpha(true)
                    }
                }
            } finally {
                tensors.forEach(OnnxTensor::close)
            }
        }

        override fun close() {
            decoder.close()
            embeddings.close()
        }

        private fun options() = OrtSession.SessionOptions().apply {
            runCatching { addConfigEntry("mlas.disable_kleidiai", "1") }
            runCatching {
                setIntraOpNumThreads(
                    Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                )
            }
        }
    }
}
