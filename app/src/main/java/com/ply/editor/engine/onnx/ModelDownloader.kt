package com.ply.editor.engine.onnx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Streams model files over HTTPS with progress, resume and integrity checks.
 *
 * - Downloads into a `.part` sibling of the destination and resumes an interrupted
 *   attempt with an HTTP `Range` request when the server supports it.
 * - Hashes the finished file with SHA-256 and only renames it into place when the
 *   digest matches, so a truncated or corrupted download never becomes loadable.
 * - If a resumed download fails verification, the partial file is discarded and
 *   the file is re-downloaded from scratch once — a stale or damaged `.part`
 *   must never be trusted twice.
 * - Cancellation is cooperative: every chunk checks [ensureActive] and the
 *   partial file stays behind for the next attempt.
 */
object ModelDownloader {

    /** Verification (size or checksum) failed; the download itself completed. */
    private class VerificationException(message: String) : IOException(message)

    private const val TAG = "PlyModelDownloader"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_BYTES = 256 * 1024
    private const val PROGRESS_STEP_BYTES = 512 * 1024

    /**
     * Download [url] into [destination].
     *
     * @param expectedSize when > 0 the downloaded file must be exactly this many bytes.
     * @param expectedSha256 when set, the finished file must hash to this hex digest.
     * @param onProgress called on the IO dispatcher with downloaded/total bytes;
     *   total may be -1 when the server does not advertise a length.
     */
    suspend fun download(
        url: String,
        destination: File,
        expectedSize: Long = 0L,
        expectedSha256: String? = null,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val directory = destination.parentFile ?: throw IOException("No parent directory")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create ${directory.path}" }
        val partial = File(directory, "${destination.name}.part")

        // A previous attempt may have left a valid prefix behind; resume it once,
        // and if verification still fails start over with a clean file.
        var allowResume = partial.isFile && partial.length() > 0L
        var attempt = 0
        while (true) {
            attempt++
            try {
                runAttempt(url, partial, expectedSize, expectedSha256, allowResume, onProgress)
                break
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                val recoverable = t is VerificationException && attempt == 1
                Log.e(TAG, "Download attempt $attempt failed", t)
                if (!recoverable) throw t
                partial.delete()
                allowResume = false
            }
        }

        if (expectedSize > 0 && partial.length() != expectedSize) {
            partial.delete()
            throw IOException("Downloaded ${partial.length()} bytes, expected $expectedSize")
        }
        expectedSha256?.let { digest ->
            val actual = sha256Hex(partial)
            if (!actual.equals(digest, ignoreCase = true)) {
                partial.delete()
                throw IOException("Checksum mismatch: $actual")
            }
        }
        if (destination.exists() && !destination.delete()) {
            throw IOException("Cannot replace ${destination.path}")
        }
        if (!partial.renameTo(destination)) {
            throw IOException("Cannot install ${destination.path}")
        }
        Log.i(TAG, "Installed ${destination.name} (${destination.length()} bytes)")
    }

    private suspend fun runAttempt(
        url: String,
        partial: File,
        expectedSize: Long,
        expectedSha256: String?,
        allowResume: Boolean,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ) {
        var downloaded = if (allowResume) partial.length() else 0L

        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = open(url, if (downloaded > 0L) downloaded else null)
            try {
                val code = connection.responseCode
                if (downloaded > 0L && (code == HttpURLConnection.HTTP_OK || code == 416)) {
                    // Either the server ignored the range, or the leftover partial
                    // file is already complete-but-rejected: start over.
                    partial.delete()
                    downloaded = 0L
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("Download failed: HTTP $code")
                }

                val total = if (code == HttpURLConnection.HTTP_PARTIAL) {
                    downloaded + contentLength(connection)
                } else {
                    contentLength(connection)
                }
                if (expectedSize > 0 && total in 1 until expectedSize) {
                    throw VerificationException("Server reported $total of $expectedSize bytes")
                }

                connection.inputStream.use { input ->
                    FileOutputStream(partial, downloaded > 0L).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var nextReport = downloaded + PROGRESS_STEP_BYTES
                        onProgress(downloaded, total)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded >= nextReport) {
                                nextReport = downloaded + PROGRESS_STEP_BYTES
                                onProgress(downloaded, total)
                            }
                        }
                        output.flush()
                    }
                }
                break
            } finally {
                connection.disconnect()
            }
        }

        if (expectedSize > 0 && partial.length() != expectedSize) {
            throw VerificationException(
                "Downloaded ${partial.length()} bytes, expected $expectedSize"
            )
        }
        expectedSha256?.let { digest ->
            val actual = sha256Hex(partial)
            if (!actual.equals(digest, ignoreCase = true)) {
                throw VerificationException("Checksum mismatch: $actual")
            }
        }
    }

    private fun open(url: String, rangeStart: Long?): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Ply Image Editor (model download)")
        if (rangeStart != null && rangeStart > 0L) {
            connection.setRequestProperty("Range", "bytes=$rangeStart-")
        }
        connection.connect()
        return connection
    }

    private fun contentLength(connection: HttpURLConnection): Long =
        connection.contentLengthLong

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
