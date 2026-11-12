package com.ply.editor.model

enum class Tool(val label: String) {
    MOVE("Move"),
    BRUSH("Brush"),
    ERASER("Eraser"),
    FILL("Fill"),
    SHAPE("Shape"),
    TEXT("Text"),
    INPAINT("Remove"),
    CROP("Crop"),
    RESIZE("Resize"),
    EYEDROPPER("Picker"),
    PAN("Hand")
}

enum class ShapeKind { RECT, ELLIPSE, LINE }

/** Input style for the object-removal mask. BACKGROUND skips the mask entirely:
 *  the selected segmentation model cuts the layer's subject out automatically. */
enum class RemoveMode(val label: String) {
    BRUSH("Brush"),
    LASSO("Lasso"),
    /** Tap objects to mask them with MobileSAM (points accumulate per tap). */
    TAP("Tap"),
    BACKGROUND("Background")
}
