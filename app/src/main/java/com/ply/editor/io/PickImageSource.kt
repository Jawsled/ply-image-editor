package com.ply.editor.io

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import com.ply.editor.R
import java.io.File

/**
 * Shows the system app chooser for images: photo picker, gallery, DocumentsUI,
 * OEM file managers, storage providers, and the camera.
 *
 * The chooser's payload is `ACTION_OPEN_DOCUMENT`, not `ACTION_GET_CONTENT`.
 * Two Android 16 behaviors make GET_CONTENT unusable for this:
 *
 * 1. The system photo picker takes over image/video GET_CONTENT, so the payload
 *    resolves to a single handler and every document provider disappears.
 * 2. Caller-supplied initial intents that duplicate a payload handler are
 *    dropped, so DocumentsUI and the OEM file manager could not be re-added
 *    through `EXTRA_INITIAL_INTENTS` either.
 *
 * OPEN_DOCUMENT is not taken over, so the payload itself lists DocumentsUI, the
 * OEM default document picker, and installed storage providers. The photo
 * picker, the gallery and the camera do not handle OPEN_DOCUMENT, so they stay
 * visible as initial intents.
 */
class PickImageSource : ActivityResultContract<Unit, Uri?>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        val pm = context.packageManager
        val documents = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        val chooser = Intent.createChooser(
            documents,
            context.getString(R.string.choose_image_source)
        )

        val extras = buildList {
            resolveTop(pm, Intent(ACTION_PICK_IMAGES).setType("image/*"))?.let(::add)
            // ACTION_PICK is what most galleries register for.
            resolveTop(pm, Intent(Intent.ACTION_PICK).setType("image/*"))?.let(::add)
            CameraCapture.newIntent(context)?.let(::add)
        }
        if (extras.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        }
        return chooser
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        val capture = CameraCapture.consume()
        val fromApp = intent?.data
        if (resultCode != Activity.RESULT_OK) {
            capture?.file?.delete()
            return null
        }
        // A few camera apps ignore EXTRA_OUTPUT and return their own URI.
        if (fromApp != null) {
            capture?.file?.delete()
            return fromApp
        }
        // Otherwise the camera wrote into our FileProvider cache file.
        if (capture != null && capture.file.length() > 0L) return capture.uri
        capture?.file?.delete()
        return null
    }
}

/** `MediaStore.ACTION_PICK_IMAGES`, inlined so the constant stays usable on API 29. */
private const val ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES"

/** Resolve an implicit intent to its top-ranked activity so it appears as one chooser tile. */
private fun resolveTop(pm: PackageManager, intent: Intent): Intent? {
    val info = pm.queryIntentActivities(intent, 0).firstOrNull() ?: return null
    val activity = info.activityInfo ?: return null
    return Intent(intent).setComponent(ComponentName(activity.packageName, activity.name))
}

/**
 * Owns the single-use capture target. The chooser may route to any camera app,
 * so the output has to exist before the intent is launched.
 */
private object CameraCapture {

    class Pending(val uri: Uri, val file: File)

    private var pending: Pending? = null

    fun newIntent(context: Context): Intent? {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        prune(dir)
        pending?.file?.delete()

        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            return null
        }
        pending = Pending(uri, file)

        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("capture", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun consume(): Pending? {
        val value = pending
        pending = null
        return value
    }

    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - DAY_MS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private const val DAY_MS = 24L * 60L * 60L * 1000L
}
