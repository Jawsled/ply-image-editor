package com.ply.editor.state

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ply.editor.engine.BrushSession
import com.ply.editor.engine.Compositor
import com.ply.editor.engine.Inpainter
import com.ply.editor.engine.Painter
import com.ply.editor.engine.onnx.BackgroundModelId
import com.ply.editor.engine.onnx.BackgroundRemover
import com.ply.editor.engine.onnx.InpaintModelId
import com.ply.editor.engine.onnx.LamaInpainter
import com.ply.editor.engine.onnx.ModelDownloader
import com.ply.editor.engine.onnx.SmartSelector
import com.ply.editor.history.History
import com.ply.editor.io.ExportFormat
import com.ply.editor.io.ExportResult
import com.ply.editor.io.Exporter
import com.ply.editor.io.Importer
import com.ply.editor.io.ProjectStore
import com.ply.editor.model.BlendMode
import com.ply.editor.model.Document
import com.ply.editor.model.Layer
import com.ply.editor.model.LayerKind
import com.ply.editor.model.LayerIds
import com.ply.editor.model.ResizeHandle
import com.ply.editor.model.RemoveMode
import com.ply.editor.model.ShapeKind
import com.ply.editor.model.handleAt
import com.ply.editor.model.resizedWith
import com.ply.editor.model.TextSpec
import com.ply.editor.model.Tool
import com.ply.editor.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MASK_MAX_EDGE = 1536f

/** Download progress for the LaMa object-removal model. */
data class LamaDownloadState(val downloadedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes > 0) {
        (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    } else 0f
    val indeterminate: Boolean get() = totalBytes <= 0
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val settings = Settings.get(app)
    private val history = History()
    private var loadedKey: String? = null
    private var saveJob: Job? = null
    @Volatile private var lastSavedDoc: Document? = null

    var doc by mutableStateOf<Document?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    var tool by mutableStateOf(Tool.MOVE)
    var color by mutableIntStateOf(0xFFB6C4FF.toInt())
        private set
    var recentColors by mutableStateOf(listOf(0xFFB6C4FF.toInt(), 0xFF34457A.toInt(), 0xFF292A2F.toInt()))
        private set
    var brushSize by mutableFloatStateOf(26f)
    var brushOpacity by mutableFloatStateOf(1f)
    var fillTolerance by mutableIntStateOf(24)
    var shapeKind by mutableStateOf(ShapeKind.RECT)
    var shapeFilled by mutableStateOf(true)
    var shapeStrokeWidth by mutableFloatStateOf(6f)
    var textSize by mutableFloatStateOf(96f)
    var textBold by mutableStateOf(false)
    var textItalic by mutableStateOf(false)

    /** Object-removal mask, painted in document space. */
    var inpaintBrushSize by mutableFloatStateOf(72f)
    var removeMode by mutableStateOf(RemoveMode.BRUSH)
        private set
    var hasInpaintMask by mutableStateOf(false)
        private set
    private var inpaintMaskBitmap: Bitmap? = null
    private var inpaintMaskScale = 1f
    private var inpaintMaskDocW = 0
    private var inpaintMaskDocH = 0
    private var inpaintSession: BrushSession? = null

    /** Mask content before the running brush stroke, so a stroke cancelled by a
     *  pinch-to-zoom gesture can be rolled back exactly. */
    private var inpaintMaskBefore: Bitmap? = null
    private var hadInpaintMask = false

    /** Points of the lasso currently being drawn, in document coordinates. */
    var inpaintLasso by mutableStateOf<List<Offset>?>(null)
        private set
    private val lassoPoints = mutableListOf<Offset>()

    /** Red mask overlay shown while the Remove tool is selected. */
    val inpaintMask: Bitmap? get() = inpaintMaskBitmap

    // ------------------------------------------------------- object removal model

    /** The engine used by the Remove tool; persisted in settings. */
    var inpaintModel by mutableStateOf(settings.inpaintModel)
        private set

    /** LaMa model presence on disk, refreshed after download. */
    var lamaReady by mutableStateOf(LamaInpainter.isDownloaded(app))
        private set

    /** Live LaMa download progress; null while idle. */
    var lamaDownload by mutableStateOf<LamaDownloadState?>(null)
        private set

    /** Last LaMa download failure message; cleared on retry. */
    var lamaError by mutableStateOf<String?>(null)
        private set

    private var lamaDownloadJob: Job? = null

    fun selectInpaintModel(model: InpaintModelId) {
        if (inpaintModel == model) return
        inpaintModel = model
        settings.updateInpaintModel(model)
        // Download progress lives in the tool options; drop stale errors on switch.
        lamaError = null
    }

    fun startLamaDownload() {
        if (lamaDownload != null || lamaReady) return
        val app = getApplication<Application>()
        lamaError = null
        lamaDownload = LamaDownloadState(0L, LamaInpainter.MODEL_SIZE_BYTES)
        lamaDownloadJob = viewModelScope.launch {
            try {
                // A stale session would pin the previous file; drop it before replacing.
                LamaInpainter.releaseSession()
                ModelDownloader.download(
                    url = LamaInpainter.MODEL_URL,
                    destination = LamaInpainter.modelFile(app),
                    expectedSize = LamaInpainter.MODEL_SIZE_BYTES,
                    expectedSha256 = LamaInpainter.MODEL_SHA256
                ) { bytes, total ->
                    lamaDownload = LamaDownloadState(bytes, total)
                }
                lamaReady = LamaInpainter.isDownloaded(app)
                lamaDownload = null
                message = if (lamaReady) "LaMa model ready" else "LaMa download failed"
            } catch (cancelled: CancellationException) {
                lamaDownload = null
                throw cancelled
            } catch (t: Throwable) {
                lamaDownload = null
                lamaReady = LamaInpainter.isDownloaded(app)
                lamaError = t.message ?: "Download failed"
            }
        }
    }

    fun cancelLamaDownload() {
        lamaDownloadJob?.cancel()
        lamaDownloadJob = null
        lamaDownload = null
    }

    /** Delete the downloaded LaMa model to free storage. */
    fun deleteLamaModel() {
        if (lamaDownload != null) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LamaInpainter.releaseSession()
                LamaInpainter.modelFile(app).delete()
            }
            lamaReady = false
            if (inpaintModel == InpaintModelId.LAMA) selectInpaintModel(InpaintModelId.MIGAN)
            message = "LaMa model deleted"
        }
    }

    // ------------------------------------------------- background removal model

    /** The segmentation engine behind the Remove tool's Background mode. */
    var bgModel by mutableStateOf(settings.bgModel)
        private set

    /** Which background model is downloading right now, if any. */
    var bgDownloadModel by mutableStateOf<BackgroundModelId?>(null)
        private set

    /** Live background-model download progress; null while idle. */
    var bgDownload by mutableStateOf<LamaDownloadState?>(null)
        private set

    /** Last background-model download failure message; cleared on retry. */
    var bgError by mutableStateOf<String?>(null)
        private set

    private var bgDownloadJob: Job? = null

    fun selectBgModel(model: BackgroundModelId) {
        if (bgModel == model || bgDownloadModel != null) return
        bgModel = model
        settings.updateBgModel(model)
        bgError = null
    }

    fun isBgModelReady(model: BackgroundModelId): Boolean =
        BackgroundRemover.isReady(getApplication(), model)

    fun startBgDownload() {
        val model = bgModel
        if (model.downloadUrl == null || bgDownloadModel != null) return
        if (BackgroundRemover.isReady(getApplication(), model)) return
        val app = getApplication<Application>()
        bgError = null
        bgDownloadModel = model
        bgDownload = LamaDownloadState(0L, model.sizeBytes)
        bgDownloadJob = viewModelScope.launch {
            try {
                // A stale session would pin the previous file; drop it first.
                BackgroundRemover.releaseSessions()
                ModelDownloader.download(
                    url = model.downloadUrl!!,
                    destination = BackgroundRemover.modelFile(app, model),
                    expectedSize = model.sizeBytes,
                    expectedSha256 = model.sha256
                ) { bytes, total ->
                    bgDownload = LamaDownloadState(bytes, total)
                }
                bgDownload = null
                bgDownloadModel = null
                message = "${model.label} model ready"
            } catch (cancelled: CancellationException) {
                bgDownload = null
                bgDownloadModel = null
                throw cancelled
            } catch (t: Throwable) {
                bgDownload = null
                bgDownloadModel = null
                bgError = t.message ?: "Download failed"
            }
        }
    }

    fun cancelBgDownload() {
        bgDownloadJob?.cancel()
        bgDownloadJob = null
        bgDownload = null
        bgDownloadModel = null
    }

    fun deleteBgModel() {
        val model = bgModel
        if (model.downloadUrl == null || bgDownloadModel != null) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                BackgroundRemover.releaseSessions()
                BackgroundRemover.modelFile(app, model).delete()
            }
            selectBgModel(BackgroundModelId.U2NET_P)
            message = "${model.label} model deleted"
        }
    }

    /** Cut the active layer's subject out with the selected background model. */
    fun applyBackground() {
        val d = doc ?: return
        if (busy != null) return
        val layer = d.active
        if (layer == null) {
            message = "Select a layer first"
            return
        }
        if (!layer.visible) {
            message = "That layer is hidden"
            return
        }
        val model = bgModel
        if (!BackgroundRemover.isReady(getApplication(), model)) {
            message = "Download the ${model.label} model first"
            return
        }
        viewModelScope.launch {
            busy = "Removing background…"
            val result = try {
                withContext(Dispatchers.Default) {
                    BackgroundRemover.cutout(getApplication(), layer.bitmap, model)
                }
            } catch (t: Throwable) {
                android.util.Log.e("PlyBgRemove", "Background removal failed", t)
                null
            }
            busy = null
            if (doc !== d) {
                message = "Canvas changed — try again"
                return@launch
            }
            if (result == null) {
                message = when {
                    BackgroundRemover.error != null ->
                        "Background: " + (BackgroundRemover.error ?: "").take(80)
                    else -> "Could not remove the background"
                }
                return@launch
            }
            commit(d, d.replaceLayer(layer.copy(bitmap = result)))
            repaintTick++
            message = "Background removed"
        }
    }

    /** Apply the object-removal engine currently selected in the Remove options. */

    var cropRect by mutableStateOf<Rect?>(null)
        private set
    var cropAspect by mutableStateOf<Float?>(null)

    var shapeStart by mutableStateOf<Offset?>(null)
        private set
    var shapeEnd by mutableStateOf<Offset?>(null)
        private set

    var pendingTextPoint by mutableStateOf<Offset?>(null)
        private set
    var editingTextLayerId by mutableStateOf<Long?>(null)
        private set

    var repaintTick by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var lastExport by mutableStateOf<ExportResult?>(null)
        private set

    /** Color copied to the clipboard by the eyedropper, plus a pulse for each pick. */
    var pickedHex by mutableStateOf<String?>(null)
        private set
    var pickPulse by mutableIntStateOf(0)
        private set

    private var session: BrushSession? = null
    private var strokeBefore: Document? = null
    private var strokeLayerId: Long = -1
    private var gestureBefore: Document? = null
    private var lastGesturePoint: Offset? = null

    /** Zoom scale exposed to the canvas for gesture tuning; 1 when unknown. */
    private var viewScaleSafe: Float = 1f

    /** The canvas calls this when its zoom changes so gesture tolerances stay
     *  constant in screen pixels. */
    fun reportViewScale(scale: Float) {
        viewScaleSafe = scale
    }

    /**
     * Snap tolerance in document pixels for the running drag, set at stroke
     * start; zero disables snapping. While a layer's center sits within the
     * tolerance of the canvas centerlines, the drag sticks to the line (guides
     * shown) — moving farther releases it naturally, so it never locks.
     */
    private var dragSnapTolerance = 0f

    /** Live snap-guide flags for the canvas overlay. */
    var snapVertical by mutableStateOf(false)
        private set
    var snapHorizontal by mutableStateOf(false)
        private set
    private var cropDragStart: Offset? = null
    private var shapeBefore: Document? = null
    private var transformBefore: Document? = null
    private var resizeBefore: Document? = null
    private var resizeStart: Layer? = null
    private var resizeHandle: ResizeHandle? = null

    fun load(target: EditorTarget) {
        if (loadedKey == target.key) return
        loadedKey = target.key
        viewModelScope.launch {
            loading = true
            val loadedDoc = withContext(Dispatchers.IO) {
                when (target) {
                    is EditorTarget.Project -> ProjectStore.load(getApplication(), target.id)
                    is EditorTarget.Image -> Importer.decode(getApplication(), target.uri)
                        ?.let { bitmap -> documentFromBitmap(bitmap) }
                    is EditorTarget.Blank -> null
                }
            }
            doc = loadedDoc ?: if (target is EditorTarget.Blank) {
                Document.blank(
                    name = target.name,
                    width = target.width.coerceIn(32, 4096),
                    height = target.height.coerceIn(32, 4096),
                    background = target.background
                )
            } else {
                loading = false
                message = when (target) {
                    is EditorTarget.Project -> "Could not open that project"
                    is EditorTarget.Image -> "Could not decode that image"
                    is EditorTarget.Blank -> null
                }
                return@launch
            }
            history.reset()
            syncHistory()
            cropRect = null
            resetInpaintMask()
            loading = false
            // Register the project straight away so a brand-new canvas appears on the
            // projects screen even if the user leaves without making an edit.
            scheduleSave()
        }
    }

    fun newDocument(width: Int, height: Int, background: Int, name: String = "Untitled") {
        doc = Document.blank(
            name = name,
            width = width.coerceIn(32, 4096),
            height = height.coerceIn(32, 4096),
            background = background
        )
        history.reset()
        syncHistory()
        cropRect = null
        resetInpaintMask()
        scheduleSave()
    }

    fun openBitmap(bitmap: Bitmap) {
        doc = documentFromBitmap(bitmap)
        history.reset()
        syncHistory()
        cropRect = null
        resetInpaintMask()
        scheduleSave()
    }

    private fun documentFromBitmap(bitmap: Bitmap): Document {
        val maxSide = 4096f
        val factor = min(1f, maxSide / max(bitmap.width, bitmap.height).toFloat())
        val source = if (factor < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).roundToInt().coerceAtLeast(1),
                (bitmap.height * factor).roundToInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        val layer = Layer(
            name = "Photo",
            bitmap = source,
            cx = source.width / 2f,
            cy = source.height / 2f
        )
        return Document(
            id = java.util.UUID.randomUUID().toString(),
            name = "Project " + java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
                .format(java.util.Date()),
            width = source.width,
            height = source.height,
            background = 0x00000000,
            layers = listOf(layer),
            activeId = layer.id
        )
    }

    // ---------------------------------------------------------------- history

    private fun syncHistory() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    private fun commit(before: Document, next: Document) {
        if (next === before) return
        history.record(before)
        doc = next
        syncHistory()
        scheduleSave()
    }

    fun undo() {
        val current = doc ?: return
        val previous = history.undo(current) ?: return
        doc = previous
        cropRect = null
        syncHistory()
        repaintTick++
        scheduleSave()
    }

    fun redo() {
        val current = doc ?: return
        val next = history.redo(current) ?: return
        doc = next
        cropRect = null
        syncHistory()
        repaintTick++
        scheduleSave()
    }

    // ------------------------------------------------------------------ tools

    private fun resetInpaintMask() {
        inpaintSession = null
        inpaintMaskBefore?.recycle()
        inpaintMaskBefore = null
        inpaintMaskBitmap = null
        inpaintMaskScale = 1f
        inpaintMaskDocW = 0
        inpaintMaskDocH = 0
        hasInpaintMask = false
        lassoPoints.clear()
        inpaintLasso = null
    }

    /** Roll the mask back to its pre-stroke content after a cancelled brush stroke. */
    private fun restoreInpaintMaskSnapshot() {
        val snapshot = inpaintMaskBefore
        inpaintMaskBefore = null
        if (snapshot == null) return
        inpaintMaskBitmap?.let { overlay ->
            overlay.eraseColor(0)
            Canvas(overlay).drawBitmap(snapshot, 0f, 0f, null)
        }
        snapshot.recycle()
        hasInpaintMask = hadInpaintMask
    }

    /** Lazily allocate a document-aligned mask, capped so huge canvases stay cheap. */
    private fun ensureInpaintMask(d: Document): Bitmap {
        val current = inpaintMaskBitmap
        if (current != null && inpaintMaskDocW == d.width && inpaintMaskDocH == d.height) {
            return current
        }
        val scale = min(1f, MASK_MAX_EDGE / max(d.width, d.height).toFloat())
        val w = (d.width * scale).roundToInt().coerceAtLeast(1)
        val h = (d.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        inpaintMaskBitmap = bitmap
        inpaintMaskScale = w / d.width.toFloat()
        inpaintMaskDocW = d.width
        inpaintMaskDocH = d.height
        hasInpaintMask = false
        return bitmap
    }

    fun clearInpaintMask() {
        resetInpaintMask()
        repaintTick++
    }

    fun selectRemoveMode(mode: RemoveMode) {
        if (removeMode == mode) return
        removeMode = mode
        cancelLasso()
        // Leaving Tap mode drops the prompt set and its encoder session.
        if (mode != RemoveMode.TAP) {
            smartPrompts = emptyList()
            SmartSelector.release()
        }
    }

    // -------------------------------------------------------- tap-to-select SAM

    /** Accumulated SAM prompts (normalized 0..1 layer coordinates) for Tap mode. */
    var smartPrompts by mutableStateOf<List<SmartSelector.Prompt>>(emptyList())
        private set

    /** Live SAM mask preview (layer-space bitmap); null before the first tap. */
    var smartMask: Bitmap? = null
        private set

    /** Whether the MobileSAM models are on disk (refreshed after download).
     *  Initialized inline — an init{} block would run before the delegates
     *  exist and crash on first editor open. */
    var smartReady by mutableStateOf(SmartSelector.isReady(app))
        private set

    var smartDownload by mutableStateOf<LamaDownloadState?>(null)
        private set

    var smartError by mutableStateOf<String?>(null)
        private set

    private var smartDownloadJob: Job? = null

    fun refreshSmartReady() {
        smartReady = SmartSelector.isReady(getApplication())
    }

    fun startSmartDownload() {
        if (smartDownload != null || SmartSelector.isReady(getApplication())) return
        val app = getApplication<Application>()
        smartError = null
        smartDownload = LamaDownloadState(0L, SmartSelector.ENCODER_BYTES + SmartSelector.DECODER_BYTES)
        smartDownloadJob = viewModelScope.launch {
            try {
                SmartSelector.release()
                data class Part(val name: String, val bytes: Long, val sha: String)
                val parts = listOf(
                    Part(
                        SmartSelector.ENCODER_FILE,
                        SmartSelector.ENCODER_BYTES,
                        SmartSelector.ENCODER_SHA256
                    ),
                    Part(
                        SmartSelector.DECODER_FILE,
                        SmartSelector.DECODER_BYTES,
                        SmartSelector.DECODER_SHA256
                    )
                )
                val total = parts.sumOf { it.bytes }
                var done = 0L
                for (part in parts) {
                    val destination = SmartSelector.modelFile(app, part.name)
                    ModelDownloader.download(
                        url = SmartSelector.url(part.name),
                        destination = destination,
                        expectedSize = part.bytes,
                        expectedSha256 = part.sha
                    ) { downloaded, _ ->
                        smartDownload = LamaDownloadState(done + downloaded, total)
                    }
                    done += part.bytes
                }
                smartDownload = null
                refreshSmartReady()
                message = "Tap-to-select ready"
            } catch (cancelled: CancellationException) {
                smartDownload = null
                throw cancelled
            } catch (t: Throwable) {
                smartDownload = null
                refreshSmartReady()
                smartError = t.message ?: "Download failed"
            }
        }
    }

    fun cancelSmartDownload() {
        smartDownloadJob?.cancel()
        smartDownloadJob = null
        smartDownload = null
    }

    fun deleteSmartModels() {
        if (smartDownload != null) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SmartSelector.release()
                SmartSelector.modelFile(app, SmartSelector.ENCODER_FILE).delete()
                SmartSelector.modelFile(app, SmartSelector.DECODER_FILE).delete()
            }
            refreshSmartReady()
            message = "Tap-to-select models deleted"
        }
    }

    /**
     * A tap in Tap mode: convert to a normalized prompt on the active layer,
     * run the decoder, and union the returned mask into the removal overlay.
     */
    fun addSmartTap(point: Offset) {
        val d = doc ?: return
        if (busy != null) return
        val layer = d.active ?: return
        if (!layer.visible) return
        if (!SmartSelector.isReady(getApplication())) {
            message = "Download the tap-to-select models first"
            return
        }
        val local = layer.toLayerSpace(point)
        if (local.x < 0 || local.y < 0 ||
            local.x > layer.bitmap.width || local.y > layer.bitmap.height
        ) {
            return
        }
        val prompt = SmartSelector.Prompt(
            local.x / layer.bitmap.width,
            local.y / layer.bitmap.height
        )
        val prompts = smartPrompts + prompt
        viewModelScope.launch {
            busy = "Selecting…"
            val mask = try {
                withContext(Dispatchers.Default) {
                    val session = SmartSelector.sessionFor(
                        getApplication(),
                        layer.id,
                        layer.bitmap
                    ) ?: return@withContext null
                    session.segment(layer.bitmap, prompts)
                }
            } catch (t: Throwable) {
                android.util.Log.e("PlySmartSelect", "Segmentation failed", t)
                null
            }
            busy = null
            if (doc !== d) {
                message = "Canvas changed — tap again"
                return@launch
            }
            if (mask == null) {
                message = if (SmartSelector.error != null) {
                    "Tap select: " + (SmartSelector.error ?: "").take(80)
                } else {
                    "Could not segment that tap"
                }
                return@launch
            }
            // Union the fresh mask into the removal overlay.
            val overlay = ensureInpaintMask(d)
            val scale = inpaintMaskScale
            val paint = Paint().apply {
                color = 0xFFFF3B30.toInt()
                style = Paint.Style.FILL
            }
            val scaled = Bitmap.createScaledBitmap(
                mask,
                (mask.width * scale).roundToInt().coerceAtLeast(1),
                (mask.height * scale).roundToInt().coerceAtLeast(1),
                false
            )
            Canvas(overlay).drawBitmap(scaled, 0f, 0f, paint)
            scaled.recycle()
            mask.recycle()
            smartPrompts = prompts
            smartMask = mask
            hasInpaintMask = true
            repaintTick++
        }
    }

    /** Clear the tap prompts and the mask they produced. */
    fun clearSmartTaps() {
        smartPrompts = emptyList()
        SmartSelector.release()
    }

    /** Start a lasso at [point] (document coordinates). */
    fun beginLasso(point: Offset) {
        if (busy != null) return
        val d = doc ?: return
        if (d.active == null) return
        lassoPoints.clear()
        lassoPoints.add(point)
        inpaintLasso = lassoPoints.toList()
        repaintTick++
    }

    fun extendLasso(point: Offset) {
        if (inpaintLasso == null) return
        val last = lassoPoints.lastOrNull()
        if (last != null && (point - last).getDistance() < 1.5f) return
        lassoPoints.add(point)
        inpaintLasso = lassoPoints.toList()
        repaintTick++
    }

    fun cancelLasso() {
        if (inpaintLasso == null && lassoPoints.isEmpty()) return
        lassoPoints.clear()
        inpaintLasso = null
        repaintTick++
    }

    /**
     * Finish the lasso: the bounding rectangle of the drawn loop becomes the mask,
     * so the four sides of the rectangle touch the loop at its extremes.
     */
    fun finishLasso() {
        val d = doc ?: return
        val points = inpaintLasso ?: return
        lassoPoints.clear()
        inpaintLasso = null
        if (points.size < 3) {
            repaintTick++
            return
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val left = minX.coerceIn(0f, d.width.toFloat())
        val top = minY.coerceIn(0f, d.height.toFloat())
        val right = maxX.coerceIn(0f, d.width.toFloat())
        val bottom = maxY.coerceIn(0f, d.height.toFloat())
        if (right - left < 2f || bottom - top < 2f) {
            repaintTick++
            return
        }
        val mask = ensureInpaintMask(d)
        val scale = inpaintMaskScale
        val paint = Paint().apply {
            color = 0x8CFF3B30.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        Canvas(mask).drawRect(
            left * scale, top * scale, right * scale, bottom * scale, paint
        )
        hasInpaintMask = true
        repaintTick++
    }

    fun applyInpaint() {
        val d = doc ?: return
        if (busy != null) return
        val layer = d.active
        if (layer == null) {
            message = "Select a layer first"
            return
        }
        if (!layer.visible) {
            message = "That layer is hidden"
            return
        }
        val mask = inpaintMaskBitmap
        if (mask == null || !hasInpaintMask) {
            message = "Paint over the object first"
            return
        }
        if (inpaintModel == InpaintModelId.LAMA && !lamaReady) {
            message = "Download the LaMa model first"
            return
        }
        val scale = inpaintMaskScale
        val model = inpaintModel
        viewModelScope.launch {
            busy = when (model) {
                InpaintModelId.CLASSIC, InpaintModelId.TELEA -> "Filling…"
                InpaintModelId.MIGAN -> "Removing object…"
                InpaintModelId.LAMA -> "Removing object with LaMa…"
            }
            val result = try {
                withContext(Dispatchers.Default) {
                    Inpainter.inpaintLayer(getApplication(), d, layer, mask, scale, model)
                }
            } catch (t: Throwable) {
                android.util.Log.e("PlyInpaint", "Object removal failed", t)
                null
            }
            busy = null
            if (doc !== d) {
                message = "Canvas changed — paint the area again"
                return@launch
            }
            if (result == null) {
                message = when {
                    model == InpaintModelId.LAMA && LamaInpainter.error != null ->
                        "LaMa: " + (LamaInpainter.error ?: "").take(80)
                    model == InpaintModelId.LAMA ->
                        "LaMa could not process that area"
                    model == InpaintModelId.MIGAN && Inpainter.error != null ->
                        "Object removal could not start on this device"
                    else -> "Could not remove that area"
                }
                return@launch
            }
            commit(d, d.replaceLayer(layer.copy(bitmap = result)))
            resetInpaintMask()
            repaintTick++
            message = "Object removed"
        }
    }

    fun selectTool(next: Tool) {
        if (tool == next) return
        tool = next
        shapeStart = null
        shapeEnd = null
        cancelLasso()
        if (next == Tool.CROP) {
            cropRect = doc?.let { defaultCropRect(it) }
        }
    }

    fun chooseColor(value: Int) {
        color = value
        val list = (listOf(value) + recentColors.filter { it != value }).take(10)
        recentColors = list
    }

    fun strokeStart(point: Offset) {
        if (busy != null) return
        val d = doc ?: return
        val layer = d.active ?: return
        if (!layer.visible) return
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                val work = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return
                // JPEG-decoded layers carry hasAlpha=false; without re-arming the
                // flag, PorterDuff CLEAR holes encode back as opaque black.
                work.setHasAlpha(true)
                strokeBefore = d
                strokeLayerId = layer.id
                doc = d.replaceLayer(layer.copy(bitmap = work))
                val widthLocal = (brushSize / layer.scale.coerceAtLeast(0.05f)).coerceAtLeast(0.5f)
                session = BrushSession(work, color, widthLocal, brushOpacity, tool == Tool.ERASER)
                session?.begin(layer.toLayerSpace(point))
                repaintTick++
            }
            Tool.INPAINT -> {
                if (removeMode != RemoveMode.BRUSH) return
                val overlay = ensureInpaintMask(d)
                // A pinch during the stroke cancels it; keep the pre-stroke mask
                // so the abandoned paint leaves no trace on the overlay.
                inpaintMaskBefore = overlay.copy(Bitmap.Config.ARGB_8888, false)
                hadInpaintMask = hasInpaintMask
                val widthLocal = (inpaintBrushSize * inpaintMaskScale).coerceAtLeast(1f)
                inpaintSession = BrushSession(
                    overlay,
                    0xFFFF3B30.toInt(),
                    widthLocal,
                    0.55f,
                    erase = false
                )
                inpaintSession?.begin(
                    Offset(point.x * inpaintMaskScale, point.y * inpaintMaskScale)
                )
                repaintTick++
            }
            Tool.MOVE -> {
                gestureBefore = d
                lastGesturePoint = point
                // Center snapping for layer drags: tolerance scales with zoom so
                // it always feels like a few screen pixels.
                dragSnapTolerance = 12f / viewScaleSafe
            }
            Tool.SHAPE -> {
                shapeBefore = d
                shapeStart = point
                shapeEnd = point
            }
            Tool.CROP -> {
                cropDragStart = point
                cropRect = Rect(point, point)
            }
            else -> Unit
        }
    }

    fun strokeMove(point: Offset) {
        val d = doc ?: return
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                val layer = d.layers.firstOrNull { it.id == strokeLayerId } ?: return
                session?.extend(layer.toLayerSpace(point))
                repaintTick++
            }
            Tool.INPAINT -> {
                inpaintSession?.extend(
                    Offset(point.x * inpaintMaskScale, point.y * inpaintMaskScale)
                )
                repaintTick++
            }
            Tool.MOVE -> {
                val layer = d.active ?: return
                val last = lastGesturePoint ?: point
                var dx = point.x - last.x
                var dy = point.y - last.y
                lastGesturePoint = point
                var cx = layer.cx + dx
                var cy = layer.cy + dy
                // Magnetic center snap: while the center is near a canvas
                // centerline it sticks to the line (guides light up), but a
                // deliberate pull beyond the tolerance moves it away naturally.
                val tolerance = dragSnapTolerance
                if (tolerance > 0f) {
                    val verticalSnap = abs(cx - d.width / 2f) < tolerance
                    val horizontalSnap = abs(cy - d.height / 2f) < tolerance
                    if (verticalSnap) cx = d.width / 2f
                    if (horizontalSnap) cy = d.height / 2f
                    snapVertical = verticalSnap
                    snapHorizontal = horizontalSnap
                }
                doc = d.replaceLayer(layer.copy(cx = cx, cy = cy))
                repaintTick++
            }
            Tool.SHAPE -> shapeEnd = point
            Tool.CROP -> {
                val start = cropDragStart ?: return
                cropRect = buildCropRect(start, point, cropAspect)
            }
            else -> Unit
        }
    }

    fun strokeEnd(point: Offset) {
        val d = doc ?: return
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                val before = strokeBefore
                session = null
                strokeBefore = null
                strokeLayerId = -1
                if (before != null) {
                    history.record(before)
                    syncHistory()
                    scheduleSave()
                }
            }
            Tool.INPAINT -> {
                if (inpaintSession != null) hasInpaintMask = true
                inpaintSession = null
                inpaintMaskBefore?.recycle()
                inpaintMaskBefore = null
                repaintTick++
            }
            Tool.MOVE -> {
                val before = gestureBefore
                gestureBefore = null
                lastGesturePoint = null
                dragSnapTolerance = 0f
                snapVertical = false
                snapHorizontal = false
                if (before != null && doc !== before) {
                    history.record(before)
                    syncHistory()
                    scheduleSave()
                }
            }
            Tool.SHAPE -> {
                val before = shapeBefore ?: return
                val start = shapeStart
                val end = shapeEnd
                shapeBefore = null
                shapeStart = null
                shapeEnd = null
                if (start != null && end != null && (abs(start.x - end.x) > 2f || abs(start.y - end.y) > 2f)) {
                    val layer = d.active ?: return
                    val work = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return
                    work.setHasAlpha(true)
                    val localStart = layer.toLayerSpace(start)
                    val localEnd = layer.toLayerSpace(end)
                    Painter.drawShape(
                        work,
                        shapeKind,
                        localStart,
                        localEnd,
                        if (shapeFilled) color else null,
                        if (shapeFilled) null else color,
                        shapeStrokeWidth / layer.scale.coerceAtLeast(0.05f)
                    )
                    commit(before, d.replaceLayer(layer.copy(bitmap = work)))
                    repaintTick++
                }
            }
            Tool.CROP -> {
                cropDragStart = null
                normalizeCrop()
            }
            else -> Unit
        }
    }

    fun cancelStroke() {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                strokeBefore?.let { doc = it }
                session = null
                strokeBefore = null
                strokeLayerId = -1
                repaintTick++
            }
            Tool.SHAPE -> {
                shapeBefore?.let { doc = it }
                shapeBefore = null
                shapeStart = null
                shapeEnd = null
                repaintTick++
            }
            Tool.INPAINT -> {
                inpaintSession = null
                restoreInpaintMaskSnapshot()
                cancelLasso()
                repaintTick++
            }
            Tool.MOVE -> {
                gestureBefore?.let { doc = it }
                gestureBefore = null
                lastGesturePoint = null
                snapVertical = false
                snapHorizontal = false
                repaintTick++
            }
            Tool.CROP -> {
                cropDragStart = null
                normalizeCrop()
            }
            else -> Unit
        }
    }

    private fun normalizeCrop() {
        val d = doc ?: return
        val rect = cropRect
        if (rect == null || rect.width < 16f || rect.height < 16f) {
            cropRect = Rect(0f, 0f, d.width.toFloat(), d.height.toFloat())
        }
    }

    fun tap(point: Offset) {
        if (busy != null) return
        val d = doc ?: return
        // A tap outside the artwork clears the selection instead of editing.
        if (point.x < 0f || point.y < 0f || point.x > d.width || point.y > d.height) {
            clearSelection()
            return
        }
        if (d.activeId == null) {
            // With nothing selected, the first tap inside the canvas picks the layer
            // under the finger so the user can carry on editing immediately.
            layerAt(d, point)?.let { picked ->
                doc = d.copy(activeId = picked.id)
                repaintTick++
            }
            return
        }
        when (tool) {
            Tool.FILL -> floodFill(point)
            Tool.EYEDROPPER -> {
                val picked = Painter.sampleColor(d, point)
                if (android.graphics.Color.alpha(picked) > 0) {
                    chooseColor(picked)
                    pickedHex = hexOf(picked)
                    pickPulse++
                    message = "Picked ${hexOf(picked)} · copied"
                } else {
                    message = "Nothing to pick here"
                }
            }
            Tool.TEXT -> {
                // Tapping an existing text layer reopens it for editing; an
                // empty spot starts a new text layer at the tap position.
                val hit = layerAt(d, point)
                if (hit != null && hit.kind == LayerKind.TEXT && hit.text != null) {
                    doc = d.copy(activeId = hit.id)
                    requestText(null)
                    repaintTick++
                } else {
                    requestText(point)
                }
            }
            Tool.CROP -> Unit
            else -> Unit
        }
    }

    private fun hexOf(color: Int): String =
        String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and color)

    /**
     * Top-most layer under [point]: an opaque pixel wins, otherwise the top layer whose
     * bounds contain the point (so empty layers are still selectable).
     */
    private fun layerAt(d: Document, point: Offset): Layer? {
        var boundsHit: Layer? = null
        for (layer in d.layers.asReversed()) {
            if (!layer.visible || layer.alpha < 0.01f) continue
            if (!layer.boundsInDocument().contains(point)) continue
            if (boundsHit == null) boundsHit = layer
            val local = layer.toLayerSpace(point)
            val x = local.x.roundToInt()
            val y = local.y.roundToInt()
            if (x < 0 || y < 0 || x >= layer.bitmap.width || y >= layer.bitmap.height) continue
            if (android.graphics.Color.alpha(layer.bitmap.getPixel(x, y)) > 0) return layer
        }
        return boundsHit
    }

    private fun floodFill(point: Offset) {
        val d = doc ?: return
        val layer = d.active ?: return
        if (!layer.visible) return
        val local = layer.toLayerSpace(point)
        val x = local.x.roundToInt()
        val y = local.y.roundToInt()
        if (x < 0 || y < 0 || x >= layer.bitmap.width || y >= layer.bitmap.height) return
        viewModelScope.launch {
            busy = "Filling…"
                        val work = withContext(Dispatchers.Default) {
                val copy = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext null
                copy.setHasAlpha(true)
                val changed = Painter.floodFill(copy, x, y, color, fillTolerance)
                if (changed) copy else null
            }
            busy = null
            if (work != null) {
                commit(d, d.replaceLayer(layer.copy(bitmap = work)))
                repaintTick++
            } else {
                message = "Nothing to fill"
            }
        }
    }

    fun transformActive(pan: Offset, zoom: Float, rotationDeg: Float) {
        val d = doc ?: return
        val layer = d.active ?: return
        if (transformBefore == null) transformBefore = d
        doc = d.replaceLayer(
            layer.copy(
                cx = layer.cx + pan.x,
                cy = layer.cy + pan.y,
                scale = (layer.scale * zoom).coerceIn(0.02f, 24f),
                rotation = layer.rotation + rotationDeg
            )
        )
        repaintTick++
    }

    /** Begin a text-chrome drag; pairs with [transformTextLayer] + [endTransform]. */
    fun beginTextTransform() {
        val d = doc ?: return
        if (d.active?.kind != LayerKind.TEXT) return
        transformBefore = d
        dragSnapTolerance = 12f / viewScaleSafe
    }

    /**
     * A clean tap (no drag) on the text chrome body: from Move mode it switches
     * to the Text tool and opens the editor; from Text mode it reopens editing
     * for the tapped layer.
     */
    fun tapTextLayer(layerId: Long) {
        val d = doc ?: return
        val layer = d.layers.firstOrNull { it.id == layerId } ?: return
        if (layer.kind != LayerKind.TEXT) return
        if (tool != Tool.TEXT) {
            tool = Tool.TEXT
            shapeStart = null
            shapeEnd = null
            cancelLasso()
        }
        if (d.activeId != layerId) doc = d.copy(activeId = layerId)
        requestText(null)
        repaintTick++
    }

    /**
     * Uniform transform for the text selection chrome: drag, pinch to scale
     * (aspect locked — a single scale factor, never per-axis), and rotate.
     * [snapEnabled] is false for handle rotations, which must never translate.
     */
    fun transformTextLayer(
        pan: Offset,
        zoom: Float,
        rotationDeg: Float,
        snapEnabled: Boolean = true
    ) {
        val d = doc ?: return
        val layer = d.active ?: return
        if (layer.kind != LayerKind.TEXT) return
        if (transformBefore == null) transformBefore = d
        var cx = layer.cx + pan.x
        var cy = layer.cy + pan.y
        val tolerance = dragSnapTolerance
        if (snapEnabled && tolerance > 0f) {
            val verticalSnap = abs(cx - d.width / 2f) < tolerance
            val horizontalSnap = abs(cy - d.height / 2f) < tolerance
            if (verticalSnap) cx = d.width / 2f
            if (horizontalSnap) cy = d.height / 2f
            snapVertical = verticalSnap
            snapHorizontal = horizontalSnap
        }
        doc = d.replaceLayer(
            layer.copy(
                cx = cx,
                cy = cy,
                scale = (layer.scale * zoom).coerceIn(0.02f, 24f),
                rotation = layer.rotation + rotationDeg
            )
        )
        repaintTick++
    }

    fun endTransform() {
        val before = transformBefore ?: return
        transformBefore = null
        dragSnapTolerance = 0f
        snapVertical = false
        snapHorizontal = false
        if (doc !== before) {
            history.record(before)
            syncHistory()
            scheduleSave()
        }
    }

    /** Delete the active text layer entirely (the selection chrome's ✕). */
    fun deleteActiveTextLayer() {
        val d = doc ?: return
        val layer = d.active ?: return
        if (layer.kind != LayerKind.TEXT) return
        if (d.layers.size <= 1) {
            message = "A document needs at least one layer"
            return
        }
        commit(d, d.removeLayer(layer.id))
        repaintTick++
        message = "Text deleted"
    }

    /** Duplicate the active text layer slightly offset (the selection chrome's ⧉). */
    fun duplicateActiveTextLayer() {
        val d = doc ?: return
        val layer = d.active ?: return
        if (layer.kind != LayerKind.TEXT) return
        val copy = layer.copy(
            id = LayerIds.next(),
            name = layer.name + " copy",
            cx = layer.cx + 24f,
            cy = layer.cy + 24f
        )
        commit(d, d.addLayer(copy, at = d.indexOf(layer.id) + 1))
        repaintTick++
        message = "Text duplicated"
    }

    /** Grip under [point] (document space) on the active layer, if any. */
    fun activeResizeHandle(point: Offset, tolerance: Float): ResizeHandle? {
        val layer = doc?.active ?: return null
        if (!layer.visible) return null
        return layer.handleAt(point, tolerance)
    }

    fun beginResize(handle: ResizeHandle) {
        val d = doc ?: return
        val layer = d.active ?: return
        if (!layer.visible) return
        resizeBefore = d
        resizeStart = layer
        resizeHandle = handle
    }

    /** Drag the active grip to [point]; every update is recomputed from the gesture start. */
    fun resizeActiveTo(point: Offset) {
        val start = resizeStart ?: return
        val handle = resizeHandle ?: return
        val d = doc ?: return
        val updated = start.resizedWith(handle, point)
        if (updated == start) return
        doc = d.replaceLayer(updated)
        repaintTick++
    }

    fun endResize() {
        val before = resizeBefore ?: return
        resizeBefore = null
        resizeStart = null
        resizeHandle = null
        if (doc !== before) {
            history.record(before)
            syncHistory()
            scheduleSave()
        }
    }

    fun moveActiveBy(dx: Float, dy: Float) {
        val d = doc ?: return
        val layer = d.active ?: return
        val before = d
        doc = d.replaceLayer(layer.copy(cx = layer.cx + dx, cy = layer.cy + dy))
        history.record(before)
        syncHistory()
        scheduleSave()
    }

    /** Move the active layer by whole document pixels; used by the nudge arrows. */
    fun nudgeActive(dx: Int, dy: Int) {
        val d = doc ?: return
        val layer = d.active
        if (layer == null) {
            message = "Select a layer first"
            return
        }
        val before = d
        doc = d.replaceLayer(layer.copy(cx = layer.cx + dx, cy = layer.cy + dy))
        history.record(before)
        syncHistory()
        scheduleSave()
        repaintTick++
    }

    /** Move the crop rectangle by whole pixels without changing its size. */
    fun nudgeCrop(dx: Int, dy: Int) {
        val d = doc ?: return
        val rect = cropRect ?: return
        val left = (rect.left + dx).coerceIn(0f, max(0f, d.width - rect.width))
        val top = (rect.top + dy).coerceIn(0f, max(0f, d.height - rect.height))
        cropRect = Rect(left, top, left + rect.width, top + rect.height)
    }

    fun scaleActiveBy(factor: Float, rotateDeg: Float) {
        val d = doc ?: return
        val layer = d.active ?: return
        val before = d
        doc = d.replaceLayer(
            layer.copy(
                scale = (layer.scale * factor).coerceIn(0.02f, 24f),
                rotation = layer.rotation + rotateDeg
            )
        )
        history.record(before)
        syncHistory()
        scheduleSave()
    }

    fun centerActiveHorizontally() {
        val d = doc ?: return
        val layer = d.active ?: return
        commit(d, d.replaceLayer(layer.copy(cx = d.width / 2f)))
    }

    fun centerActiveVertically() {
        val d = doc ?: return
        val layer = d.active ?: return
        commit(d, d.replaceLayer(layer.copy(cy = d.height / 2f)))
    }

    fun centerActive() {
        val d = doc ?: return
        val layer = d.active ?: return
        commit(d, d.replaceLayer(layer.copy(cx = d.width / 2f, cy = d.height / 2f)))
    }

    // ------------------------------------------------------------------ text

    fun requestText(at: Offset?) {
        val d = doc ?: return
        val active = d.active
        pendingTextPoint = at ?: Offset(d.width / 2f, d.height / 2f)
        // Tapping the canvas always starts a fresh text layer; the tool's
        // "Add text" button edits the active text layer when there is one.
        editingTextLayerId = if (at == null && active?.kind == LayerKind.TEXT && active.text != null) {
            active.id
        } else {
            null
        }
        if (editingTextLayerId != null) {
            active?.text?.let { spec ->
                textSize = spec.sizePx
                textBold = spec.bold
                textItalic = spec.italic
                chooseColor(spec.color)
            }
        }
    }

    fun dismissTextRequest() {
        pendingTextPoint = null
        editingTextLayerId = null
    }

    fun addText(spec: TextSpec, at: Offset?) {
        val d = doc ?: return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                Painter.renderText(spec)
            }
            val center = at ?: Offset(d.width / 2f, d.height / 2f)
            val layer = Layer(
                name = spec.text.lineSequence().firstOrNull()?.take(24)?.ifBlank { "Text" } ?: "Text",
                bitmap = bitmap,
                kind = LayerKind.TEXT,
                cx = center.x + bitmap.width / 2f,
                cy = center.y + bitmap.height / 2f,
                text = spec
            )
            pendingTextPoint = null
            editingTextLayerId = null
            commit(d, d.addLayer(layer))
            repaintTick++
        }
    }

    fun updateTextLayer(layerId: Long, spec: TextSpec) {
        val d = doc ?: return
        val layer = d.layers.firstOrNull { it.id == layerId } ?: return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                Painter.renderText(spec)
            }
            pendingTextPoint = null
            editingTextLayerId = null
            commit(
                d,
                d.replaceLayer(
                    layer.copy(
                        bitmap = bitmap,
                        kind = LayerKind.TEXT,
                        text = spec,
                        name = spec.text.lineSequence().firstOrNull()?.take(24)?.ifBlank { "Text" } ?: "Text"
                    )
                )
            )
            repaintTick++
        }
    }

    // ---------------------------------------------------------------- layers

    private fun activeLayer(): Layer? = doc?.active

    fun selectLayer(id: Long) {
        val d = doc ?: return
        if (d.activeId == id) return
        doc = d.copy(activeId = id)
        repaintTick++
    }

    /** Drop the selection so no layer receives edits; tapping the canvas restores nothing. */
    fun clearSelection() {
        val d = doc ?: return
        if (d.activeId == null) return
        doc = d.copy(activeId = null)
        cropRect = null
        repaintTick++
    }

    fun addEmptyLayer() {
        val d = doc ?: return
        val bitmap = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val layer = Layer(
            name = "Layer ${d.layers.size + 1}",
            bitmap = bitmap,
            kind = LayerKind.RASTER,
            cx = d.width / 2f,
            cy = d.height / 2f
        )
        commit(d, d.addLayer(layer))
    }

    fun addImageLayer(bitmap: Bitmap) {
        val d = doc ?: return
        val fit = min(1f, min(d.width * 0.85f / bitmap.width, d.height * 0.85f / bitmap.height))
        val layer = Layer(
            name = "Image",
            bitmap = bitmap,
            cx = d.width / 2f,
            cy = d.height / 2f,
            scale = if (fit.isFinite() && fit > 0f) fit else 1f
        )
        commit(d, d.addLayer(layer))
    }

    fun addImageFromUri(uri: Uri) {
        viewModelScope.launch {
            busy = "Importing…"
            val bitmap = withContext(Dispatchers.IO) { Importer.decode(getApplication(), uri) }
            busy = null
            if (bitmap != null) addImageLayer(bitmap) else message = "Could not open that image"
        }
    }

    fun duplicateActive() {
        val d = doc ?: return
        val layer = activeLayer() ?: return
        val copy = layer.copy(
            id = LayerIds.next(),
            name = layer.name + " copy",
            cx = layer.cx + 32f,
            cy = layer.cy + 32f
        )
        commit(d, d.addLayer(copy, at = d.indexOf(layer.id) + 1))
    }

    fun deleteActive() {
        val d = doc ?: return
        val layer = d.active ?: return
        if (d.layers.size <= 1) {
            message = "A document needs at least one layer"
            return
        }
        commit(d, d.removeLayer(layer.id))
    }

    fun mergeDown() {
        val d = doc ?: return
        val active = d.active
        if (active == null) {
            message = "Select a layer first"
            return
        }
        val index = d.indexOf(active.id)
        if (index <= 0) {
            message = "No layer below to merge into"
            return
        }
        val lower = d.layers[index - 1]
        val upper = d.layers[index]
        val merged = Compositor.flattenRange(d, listOf(lower, upper))
        val layers = d.layers.toMutableList()
        layers.removeAt(index)
        layers[index - 1] = merged
        commit(d, d.copy(layers = layers, activeId = merged.id))
    }

    fun flatten() {
        val d = doc ?: return
        if (d.layers.size <= 1) return
        val merged = Compositor.flatten(d)
        commit(d, d.copy(layers = listOf(merged), activeId = merged.id))
    }

    fun moveLayer(up: Boolean) {
        val d = doc ?: return
        val active = d.active ?: return
        commit(d, d.moveLayer(active.id, if (up) 1 else -1))
    }

    fun setLayerVisible(layerId: Long, visible: Boolean) {
        val d = doc ?: return
        val layer = d.layers.firstOrNull { it.id == layerId } ?: return
        commit(d, d.replaceLayer(layer.copy(visible = visible)))
    }

    fun setLayerAlpha(layerId: Long, alpha: Float) {
        val d = doc ?: return
        val layer = d.layers.firstOrNull { it.id == layerId } ?: return
        doc = d.replaceLayer(layer.copy(alpha = alpha.coerceIn(0f, 1f)))
        scheduleSave()
    }

    fun commitLayerAlpha(before: Document) {
        if (doc !== before) {
            history.record(before)
            syncHistory()
        }
    }

    fun setLayerBlend(layerId: Long, blend: BlendMode) {
        val d = doc ?: return
        val layer = d.layers.firstOrNull { it.id == layerId } ?: return
        commit(d, d.replaceLayer(layer.copy(blend = blend)))
    }

    fun renameLayer(layerId: Long, name: String) {
        val d = doc ?: return
        val layer = d.layers.firstOrNull { it.id == layerId } ?: return
        commit(d, d.replaceLayer(layer.copy(name = name.trim().ifBlank { "Layer" })))
    }

    fun clearActiveLayer() {
        val d = doc ?: return
        val layer = d.active ?: return
        val blank = Bitmap.createBitmap(layer.bitmap.width, layer.bitmap.height, Bitmap.Config.ARGB_8888)
        commit(d, d.replaceLayer(layer.copy(bitmap = blank, text = null, kind = LayerKind.RASTER)))
        repaintTick++
    }

    fun fillActive() {
        val d = doc ?: return
        val layer = d.active ?: return
        val work = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return
        work.setHasAlpha(true)
        work.eraseColor(color)
        commit(d, d.replaceLayer(layer.copy(bitmap = work, text = null, kind = LayerKind.RASTER)))
        repaintTick++
    }

    /** Rotate the active layer's pixels by 90 degrees around its center. */
    fun rotateActive(clockwise: Boolean) {
        val d = doc ?: return
        val layer = d.active ?: return
        val src = layer.bitmap
        if (src.width <= 0 || src.height <= 0) return
        val matrix = Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true) ?: return
        commit(
            d,
            d.replaceLayer(
                layer.copy(
                    bitmap = rotated,
                    // Bitmap axes swapped, so swap the per-axis stretch as well and the
                    // layer keeps the same on-screen footprint after the quarter turn.
                    scaleX = layer.scaleY,
                    scaleY = layer.scaleX
                )
            )
        )
        repaintTick++
    }

    fun flipActive(horizontal: Boolean) {
        val d = doc ?: return
        val layer = d.active ?: return
        val src = layer.bitmap
        if (src.width <= 0 || src.height <= 0) return
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (horizontal) {
            canvas.scale(-1f, 1f, src.width / 2f, src.height / 2f)
        } else {
            canvas.scale(1f, -1f, src.width / 2f, src.height / 2f)
        }
        canvas.drawBitmap(src, 0f, 0f, null)
        commit(d, d.replaceLayer(layer.copy(bitmap = out)))
        repaintTick++
    }

    /** Scale the active layer so its current visual bounds fit the canvas, then center it. */
    fun autoFitActive() {
        val d = doc ?: return
        val layer = d.active ?: return
        val bounds = layer.boundsInDocument()
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val factor = min(d.width / bounds.width, d.height / bounds.height)
        commit(
            d,
            d.replaceLayer(
                layer.copy(
                    scale = (layer.scale * factor).coerceIn(0.02f, 24f),
                    cx = d.width / 2f,
                    cy = d.height / 2f
                )
            )
        )
        repaintTick++
    }

    fun resetActiveStretch() {
        val d = doc ?: return
        val layer = d.active ?: return
        if (layer.scaleX == 1f && layer.scaleY == 1f) return
        commit(d, d.replaceLayer(layer.copy(scaleX = 1f, scaleY = 1f)))
        repaintTick++
    }

    // ------------------------------------------------------------- adjustments

    fun applyAdjustments(brightness: Float, contrast: Float, saturation: Float) {
        val d = doc ?: return
        val layer = d.active ?: return
        viewModelScope.launch {
            busy = "Adjusting…"
            val adjusted = withContext(Dispatchers.Default) {
                Painter.adjust(layer.bitmap, brightness, contrast, saturation)
            }
            busy = null
            commit(d, d.replaceLayer(layer.copy(bitmap = adjusted)))
            repaintTick++
        }
    }

    // ------------------------------------------------------------------ canvas

    fun updateCrop(start: Offset, end: Offset) {
        cropRect = buildCropRect(start, end, cropAspect)
    }

    private fun buildCropRect(start: Offset, end: Offset, aspect: Float?): Rect {
        val d = doc ?: return Rect(start, end)
        var left = min(start.x, end.x).coerceIn(0f, d.width.toFloat())
        var top = min(start.y, end.y).coerceIn(0f, d.height.toFloat())
        var right = max(start.x, end.x).coerceIn(0f, d.width.toFloat())
        var bottom = max(start.y, end.y).coerceIn(0f, d.height.toFloat())
        if (aspect != null && aspect > 0f) {
            var w = right - left
            var h = bottom - top
            if (w / h > aspect) w = h * aspect else h = w / aspect
            if (start.x <= end.x) right = left + w else left = right - w
            if (start.y <= end.y) bottom = top + h else top = bottom - h
            if (left < 0f) { right -= left; left = 0f }
            if (top < 0f) { bottom -= top; top = 0f }
            if (right > d.width) { left -= (right - d.width); right = d.width.toFloat() }
            if (bottom > d.height) { top -= (bottom - d.height); bottom = d.height.toFloat() }
            left = left.coerceAtLeast(0f)
            top = top.coerceAtLeast(0f)
        }
        if (right - left < 1f) right = min(left + 1f, d.width.toFloat())
        if (bottom - top < 1f) bottom = min(top + 1f, d.height.toFloat())
        return Rect(left, top, right, bottom)
    }

    fun applyCrop() {
        val d = doc ?: return
        val rect = cropRect
        if (rect == null) {
            message = "Select a layer first"
            return
        }
        if (rect.width < 16f || rect.height < 16f) {
            message = "Crop area is too small"
            return
        }
        val layer = d.active
        if (layer == null) {
            message = "Select a layer first"
            return
        }

        val left = floor(rect.left).toInt().coerceIn(0, d.width - 1)
        val top = floor(rect.top).toInt().coerceIn(0, d.height - 1)
        val right = ceil(rect.right).toInt().coerceIn(left + 1, d.width)
        val bottom = ceil(rect.bottom).toInt().coerceIn(top + 1, d.height)
        val w = right - left
        val h = bottom - top

        val bounds = layer.boundsInDocument()
        if (bounds.right <= left || bounds.left >= right ||
            bounds.bottom <= top || bounds.top >= bottom
        ) {
            message = "Nothing on this layer to crop"
            return
        }

        // Bake the layer's transform into a document-space crop. Rendering above 1:1
        // keeps detail when the layer was scaled up, without exceeding 4096 px.
        val quality = min(
            layer.scale.coerceIn(1f, 3f),
            4096f / max(w, h).toFloat()
        )
        val out = Bitmap.createBitmap(
            (w * quality).roundToInt().coerceAtLeast(1),
            (h * quality).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        canvas.scale(quality, quality)
        canvas.translate(-left.toFloat(), -top.toFloat())
        canvas.concat(layer.matrix())
        canvas.drawBitmap(
            layer.bitmap,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        val wasText = layer.kind == LayerKind.TEXT || layer.text != null
        val croppedLayer = layer.copy(
            bitmap = out,
            cx = left + w / 2f,
            cy = top + h / 2f,
            scale = 1f / quality,
            rotation = 0f,
            kind = if (wasText) LayerKind.RASTER else layer.kind,
            text = null
        )
        commit(d, d.replaceLayer(croppedLayer))
        cropRect = null
        resetInpaintMask()
        tool = Tool.MOVE
        repaintTick++
        message = if (wasText) "Cropped · text rasterized" else "Cropped ${layer.name}"
    }

    fun resetCropRect() {
        val d = doc ?: return
        cropRect = defaultCropRect(d)
    }

    /** Crop starts on the active layer's bounds, so the selection communicates its scope. */
    private fun defaultCropRect(d: Document): Rect {
        val bounds = d.active?.boundsInDocument()
        if (bounds != null) {
            val left = bounds.left.coerceIn(0f, d.width.toFloat())
            val top = bounds.top.coerceIn(0f, d.height.toFloat())
            val right = bounds.right.coerceIn(0f, d.width.toFloat())
            val bottom = bounds.bottom.coerceIn(0f, d.height.toFloat())
            if (right - left >= 16f && bottom - top >= 16f) {
                return Rect(left, top, right, bottom)
            }
        }
        return Rect(0f, 0f, d.width.toFloat(), d.height.toFloat())
    }

    fun resizeCanvas(width: Int, height: Int) {
        val d = doc ?: return
        commit(d, d.resizedCanvas(width.coerceIn(32, 4096), height.coerceIn(32, 4096)))
        resetInpaintMask()
        repaintTick++
    }

    /**
     * Scale the whole composition. Always relative to the document's original
     * dimensions — never compounding — so tapping "50%" repeatedly stays at 50%.
     */
    fun scaleContent(factor: Float) {
        val d = doc ?: return
        val target = if (d.width == d.originalWidth && d.height == d.originalHeight) {
            d.scaledContent(factor)
        } else {
            // Reverse the previous scaling first, then apply the new factor.
            val undo = d.width.toFloat() / d.originalWidth
            d.copy(width = d.originalWidth, height = d.originalHeight)
                .rescaleContent(undo)
                .scaledContent(factor)
        }
        commit(d, target)
        resetInpaintMask()
        repaintTick++
    }

    fun setBackground(color: Int) {
        val d = doc ?: return
        doc = d.copy(background = color)
        scheduleSave()
    }

    fun renameDocument(name: String) {
        val d = doc ?: return
        doc = d.copy(name = name.trim().ifBlank { "Untitled" })
        scheduleSave()
    }

    // -------------------------------------------------------------- persistence

    private fun scheduleSave() {
        val d = doc ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500)
            val saved = withContext(Dispatchers.IO) { ProjectStore.save(getApplication(), d) }
            if (saved) lastSavedDoc = d
        }
    }

    fun saveNow() {
        val d = doc ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { ProjectStore.save(getApplication(), d) }
            if (saved) lastSavedDoc = d
        }
    }

    /** Save off the main thread and suspend until the files are on disk. */
    suspend fun saveAndWait() {
        saveJob?.cancel()
        saveJob = null
        val d = doc ?: return
        val saved = withContext(Dispatchers.IO) { ProjectStore.save(getApplication(), d) }
        if (saved) lastSavedDoc = d
    }

    /** Called when the editor leaves the screen; must not depend on viewModelScope. */
    fun saveBlocking() {
        saveJob?.cancel()
        saveJob = null
        val d = doc ?: return
        if (d === lastSavedDoc) return
        val app = getApplication<Application>()
        Thread {
            if (ProjectStore.save(app, d)) lastSavedDoc = d
        }.apply {
            name = "ply-project-save"
            start()
        }
    }

    fun export(format: ExportFormat, quality: Int) {
        val d = doc ?: return
        viewModelScope.launch {
            busy = "Exporting…"
            val result = withContext(Dispatchers.IO) {
                Exporter.export(getApplication(), d, format, quality, d.name)
            }
            busy = null
            if (result != null) {
                lastExport = result
                message = "Saved to Pictures/Ply"
            } else {
                message = "Export failed"
            }
        }
    }

    fun duplicateProjectAndOpen(): String? {
        val d = doc ?: return null
        val copy = d.copy(id = java.util.UUID.randomUUID().toString(), name = d.name + " copy")
        return if (ProjectStore.save(getApplication(), copy)) copy.id else null
    }

    fun consumeMessage() {
        message = null
    }
}
