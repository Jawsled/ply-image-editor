package com.ply.editor.engine.onnx

/**
 * Object-removal engines known to the app.
 *
 * CLASSIC and TELEA are pure image processing with no neural network — the
 * pre-ONNX fills, kept for low-end devices and quick touch-ups (BFS nearest
 * color, and edge-aware fast marching respectively).
 *
 * MI-GAN ships inside the APK as a portable `.ort` asset, so it works offline.
 * LaMa is the ImageToolbox-style dynamic-resolution pipeline: the `LaMa_dynamic.onnx`
 * graph is downloaded once from the project's HuggingFace model repository, verified
 * against the published SHA-256, and kept in `filesDir/onnx`.
 */
enum class InpaintModelId(
    val label: String,
    val blurb: String,
    val needsDownload: Boolean,
    val approximateBytes: Long
) {
    MIGAN(
        label = "MI-GAN",
        blurb = "Bundled neural model · works offline · fast on small objects",
        needsDownload = false,
        approximateBytes = 28_000_000L
    ),
    CLASSIC(
        label = "Classic",
        blurb = "Original BFS fill, no AI model · instant on any device",
        needsDownload = false,
        approximateBytes = 0L
    ),
    TELEA(
        label = "Telea",
        blurb = "Edge-aware fast-marching fill, no AI model · instant",
        needsDownload = false,
        approximateBytes = 0L
    ),
    LAMA(
        label = "LaMa",
        blurb = "Dynamic-resolution big-LaMa · tiled on large masks · 196 MB one-time download",
        needsDownload = true,
        approximateBytes = 205_294_245L
    );

    companion object {
        fun fromStored(value: String?): InpaintModelId =
            entries.firstOrNull { it.name == value } ?: MIGAN
    }
}

/**
 * Background-removal models (see [BackgroundRemover]). U2NET_P ships inside the
 * APK; the larger segmentation models stream from the same HuggingFace model
 * repository the LaMa graph comes from.
 */
enum class BackgroundModelId(
    val label: String,
    val blurb: String,
    val fileName: String,
    val trainedSize: Int,
    /** Null for the bundled model; the HuggingFace path for downloadable ones. */
    val downloadPath: String?,
    val sizeBytes: Long,
    val sha256: String?
) {
    U2NET_P(
        label = "U2Net P",
        blurb = "bundled · offline · instant",
        fileName = "u2netp.onnx",
        trainedSize = 320,
        downloadPath = null,
        sizeBytes = 4_574_861L,
        sha256 = null
    ),
    ISNET(
        label = "ISNet",
        blurb = "1024 px · sharper edges · 178 MB download",
        fileName = "isnet-general-use.onnx",
        trainedSize = 1024,
        downloadPath = "isnet-general-use.onnx",
        sizeBytes = 178_647_984L,
        sha256 = "4fcc3f7f7af1d16565dd7ec767e6e2500565ed6ba76c5c30b9934116ca32153e"
    ),
    BIREFNET(
        label = "BiRefNet",
        blurb = "1024 px · sharpest cutouts · 224 MB download",
        fileName = "birefnet_swin_tiny.onnx",
        trainedSize = 1024,
        downloadPath = "birefnet_swin_tiny.onnx",
        sizeBytes = 224_005_046L,
        sha256 = "efd35f77ea8ff79dc655968699e02599317670ebdbe395034454234ab2652b0e"
    );

    /** Remote URL or null when the model ships inside the APK. */
    val downloadUrl: String?
        get() = downloadPath?.let {
            "https://huggingface.co/T8RIN/imagetoolbox-models/resolve/main/$it"
        }

    companion object {
        fun fromStored(value: String?): BackgroundModelId =
            entries.firstOrNull { it.name == value } ?: U2NET_P
    }
}
