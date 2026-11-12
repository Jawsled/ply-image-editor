package com.ply.editor.history

import android.graphics.Bitmap
import com.ply.editor.model.Document
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Snapshot history. Layers are immutable data classes holding immutable bitmaps,
 * so snapshots share every untouched bitmap. The budget prunes old snapshots that
 * would otherwise keep large bitmaps alive.
 */
class History(
    private val maxSteps: Int = 30,
    private val maxBytes: Long = 160L * 1024 * 1024
) {
    private val undoStack = ArrayDeque<Document>()
    private val redoStack = ArrayDeque<Document>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    fun record(before: Document) {
        undoStack.addLast(before)
        redoStack.clear()
        trim()
    }

    fun undo(current: Document): Document? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return previous
    }

    fun redo(current: Document): Document? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        trim()
        return next
    }

    fun reset() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun trim() {
        while (undoStack.size > maxSteps) undoStack.removeFirst()
        while (undoStack.size > 1 && estimateBytes() > maxBytes) undoStack.removeFirst()
    }

    private fun estimateBytes(): Long {
        val seen = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        var total = 0L
        fun count(doc: Document) {
            doc.layers.forEach { layer ->
                if (seen.add(layer.bitmap)) {
                    total += layer.bitmap.allocationByteCount.toLong()
                }
            }
        }
        undoStack.forEach(::count)
        redoStack.forEach(::count)
        return total
    }
}
