package com.ply.editor.io

import android.content.Context
import android.graphics.Bitmap
import com.ply.editor.engine.Compositor
import com.ply.editor.model.BlendMode
import com.ply.editor.model.Document
import com.ply.editor.model.Layer
import com.ply.editor.model.LayerIds
import com.ply.editor.model.LayerKind
import com.ply.editor.model.TextAlignSpec
import com.ply.editor.model.TextSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val layerCount: Int,
    val updatedAt: Long,
    val thumbnail: Bitmap?
)

object ProjectStore {
    private const val MANIFEST = "project.json"
    private const val THUMB = "thumb.png"
    private const val JPEG_QUALITY = 95
    private const val THUMB_SIZE = 720

    /** docId/layerId -> identity of the bitmap already written to disk, plus its encoding. */
    private val writtenBitmapIds = HashMap<String, Pair<Int, Boolean>>()
    private val ioLock = Any()

    private fun projectsRoot(context: Context): File = File(context.filesDir, "projects")

    fun projectDir(context: Context, id: String): File = File(projectsRoot(context), id)

    fun save(context: Context, doc: Document): Boolean = synchronized(ioLock) {
        return try {
            val dir = projectDir(context, doc.id)
            val layersDir = File(dir, "layers").apply { mkdirs() }
            val json = JSONObject().apply {
                put("version", 1)
                put("id", doc.id)
                put("name", doc.name)
                put("width", doc.width)
                put("height", doc.height)
                put("background", doc.background)
                doc.activeId?.let { put("activeId", it) }
                put("updatedAt", System.currentTimeMillis())
                val arr = JSONArray()
                doc.layers.forEach { layer ->
                    val key = "${doc.id}/${layer.id}"
                    val identity = System.identityHashCode(layer.bitmap)
                    // Reuse the previous decision for an unchanged bitmap instead of
                    // rescanning millions of pixels on every autosave.
                    val cached = writtenBitmapIds[key]
                    val opaque = if (cached != null && cached.first == identity) {
                        cached.second
                    } else {
                        isOpaque(layer.bitmap)
                    }
                    val extension = if (opaque) "jpg" else "png"
                    val fileName = "layers/${layer.id}.$extension"
                    val file = File(dir, fileName)
                    if (!file.exists() || file.length() == 0L || cached?.first != identity) {
                        if (!writeLayer(file, layer.bitmap, opaque)) {
                            throw IllegalStateException("Could not write ${layer.id}")
                        }
                        writtenBitmapIds[key] = identity to opaque
                        // Drop the previous encoding if the layer changed between
                        // opaque (JPEG) and transparent (PNG).
                        val stale = File(layersDir, "${layer.id}.${if (opaque) "png" else "jpg"}")
                        if (stale.exists()) stale.delete()
                    }
                    arr.put(JSONObject().apply {
                        put("id", layer.id)
                        put("name", layer.name)
                        put("file", fileName)
                        put("kind", layer.kind.name)
                        put("cx", layer.cx.toDouble())
                        put("cy", layer.cy.toDouble())
                        put("scale", layer.scale.toDouble())
                        put("scaleX", layer.scaleX.toDouble())
                        put("scaleY", layer.scaleY.toDouble())
                        put("rotation", layer.rotation.toDouble())
                        put("alpha", layer.alpha.toDouble())
                        put("visible", layer.visible)
                        put("blend", layer.blend.name)
                        layer.text?.let { t ->
                            put("text", JSONObject().apply {
                                put("text", t.text)
                                put("sizePx", t.sizePx.toDouble())
                                put("color", t.color)
                                put("bold", t.bold)
                                put("italic", t.italic)
                                put("align", t.align.name)
                            })
                        }
                    })
                }
                put("layers", arr)
            }
            val tmp = File(dir, "$MANIFEST.tmp")
            tmp.writeText(json.toString())
            val manifest = File(dir, MANIFEST)
            if (!tmp.renameTo(manifest)) {
                manifest.writeText(json.toString())
                tmp.delete()
            }

            val thumb = Compositor.thumbnail(doc, THUMB_SIZE)
            val thumbTmp = File(dir, "$THUMB.tmp")
            val thumbFile = File(dir, THUMB)
            val thumbWritten = FileOutputStream(thumbTmp).use { out ->
                BufferedOutputStream(out, 64 * 1024).use { buffered ->
                    thumb.compress(Bitmap.CompressFormat.PNG, 90, buffered)
                }
            }
            if (thumbWritten) {
                // Rename atomically: the home screen may be decoding tile previews
                // while a save is in flight.
                if (!thumbTmp.renameTo(thumbFile)) {
                    thumbFile.delete()
                    if (!thumbTmp.renameTo(thumbFile)) {
                        thumbTmp.copyTo(thumbFile, overwrite = true)
                    }
                }
            }
            thumbTmp.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Opaque layers (imported photos, flattened artwork) are stored as JPEG: encoding a
     * 12 MP photo as PNG takes seconds, while JPEG is several times faster and much
     * smaller. Layers with transparency still use lossless PNG.
     */
    private fun isOpaque(bitmap: Bitmap): Boolean {
        if (bitmap.config == Bitmap.Config.RGB_565) return true
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if ((row[x] ushr 24) != 0xFF) return false
            }
        }
        return true
    }

    private fun writeLayer(file: File, bitmap: Bitmap, opaque: Boolean): Boolean {
        val format = if (opaque) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        val quality = if (opaque) JPEG_QUALITY else 100
        if (!opaque && !bitmap.hasAlpha()) {
            // The pixels may carry eraser holes while the bitmap is still flagged
            // opaque (JPEG heritage): encoding with that flag writes an
            // alpha-less PNG and the holes come back black.
            bitmap.setHasAlpha(true)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            val written = FileOutputStream(tmp).use { fos ->
                BufferedOutputStream(fos, 128 * 1024).use { out ->
                    bitmap.compress(format, quality, out)
                }
            }
            if (!written) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            }
            tmp.delete()
            true
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    fun load(context: Context, id: String): Document? {
        return try {
            val dir = projectDir(context, id)
            val manifest = File(dir, MANIFEST)
            if (!manifest.exists()) return null
            val json = JSONObject(manifest.readText())
            val arr = json.getJSONArray("layers")
            val layers = ArrayList<Layer>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = File(dir, o.getString("file"))
                if (!file.exists()) continue
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.path) ?: continue
                // The decoded bitmap already matches the file on disk, so the first
                // autosave does not need to re-encode an unchanged layer.
                synchronized(ioLock) {
                    writtenBitmapIds["$id/${o.getLong("id")}"] = System.identityHashCode(bitmap) to
                        file.name.endsWith(".jpg", ignoreCase = true)
                }
                val text = o.optJSONObject("text")?.let { t ->
                    TextSpec(
                        text = t.optString("text"),
                        sizePx = t.optDouble("sizePx", 64.0).toFloat(),
                        color = t.optInt("color", 0xFF000000.toInt()),
                        bold = t.optBoolean("bold"),
                        italic = t.optBoolean("italic"),
                        align = runCatching { TextAlignSpec.valueOf(t.optString("align")) }
                            .getOrDefault(TextAlignSpec.CENTER)
                    )
                }
                layers.add(
                    Layer(
                        id = o.getLong("id"),
                        name = o.optString("name", "Layer"),
                        bitmap = bitmap,
                        kind = runCatching { LayerKind.valueOf(o.optString("kind")) }
                            .getOrDefault(LayerKind.RASTER),
                        cx = o.optDouble("cx", bitmap.width / 2.0).toFloat(),
                        cy = o.optDouble("cy", bitmap.height / 2.0).toFloat(),
                        scale = o.optDouble("scale", 1.0).toFloat(),
                        scaleX = o.optDouble("scaleX", 1.0).toFloat(),
                        scaleY = o.optDouble("scaleY", 1.0).toFloat(),
                        rotation = o.optDouble("rotation", 0.0).toFloat(),
                        alpha = o.optDouble("alpha", 1.0).toFloat(),
                        visible = o.optBoolean("visible", true),
                        blend = runCatching { BlendMode.valueOf(o.optString("blend")) }
                            .getOrDefault(BlendMode.NORMAL),
                        text = text
                    )
                )
            }
            if (layers.isEmpty()) return null
            // Keep the process-wide id counter above every persisted id so a
            // newly added layer can never reuse one (duplicate-key crash).
            layers.forEach { LayerIds.ensureAbove(it.id) }
            val savedActive = if (json.has("activeId") && !json.isNull("activeId")) {
                json.optLong("activeId")
            } else {
                layers.last().id
            }
            Document(
                id = json.optString("id", id),
                name = json.optString("name", "Untitled"),
                width = json.optInt("width", layers[0].bitmap.width),
                height = json.optInt("height", layers[0].bitmap.height),
                background = json.optInt("background", 0xFFFFFFFF.toInt()),
                layers = layers,
                activeId = savedActive.takeIf { active -> layers.any { it.id == active } }
                    ?: layers.last().id
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun summaries(context: Context): List<ProjectSummary> {
        val root = projectsRoot(context)
        if (!root.exists()) return emptyList()
        val out = ArrayList<ProjectSummary>()
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            try {
                val manifest = File(dir, MANIFEST)
                if (!manifest.exists()) return@forEach
                val json = JSONObject(manifest.readText())
                val thumbFile = File(dir, THUMB)
                val thumb = if (thumbFile.exists()) {
                    android.graphics.BitmapFactory.decodeFile(thumbFile.path)
                } else null
                out.add(
                    ProjectSummary(
                        id = json.optString("id", dir.name),
                        name = json.optString("name", "Untitled"),
                        width = json.optInt("width"),
                        height = json.optInt("height"),
                        layerCount = json.optJSONArray("layers")?.length() ?: 0,
                        updatedAt = json.optLong("updatedAt"),
                        thumbnail = thumb
                    )
                )
            } catch (_: Throwable) {
                // skip unreadable project
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun delete(context: Context, id: String) = synchronized(ioLock) {
        projectDir(context, id).deleteRecursively()
        writtenBitmapIds.keys.removeAll { it.startsWith("$id/") }
    }

    fun duplicate(context: Context, id: String): ProjectSummary? {
        val doc = load(context, id) ?: return null
        val copy = doc.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = doc.name + " copy"
        )
        return if (save(context, copy)) {
            summaries(context).firstOrNull { it.id == copy.id }
        } else null
    }
}
