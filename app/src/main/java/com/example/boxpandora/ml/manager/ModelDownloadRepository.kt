package com.example.boxpandora.ml.manager

import android.util.Log
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ml.model.ModelSource
import com.example.boxpandora.ml.storage.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG              = "ModelDownloadRepo"
private const val BUFFER_SIZE      = 16 * 1024
private const val CONNECT_TIMEOUT  = 30_000
private const val READ_TIMEOUT     = 60_000

// ---------------------------------------------------------------------------
// Result type
// ---------------------------------------------------------------------------

/**
 * Result returned by [ModelDownloadRepository.download].
 *
 * On [Success] the caller receives a temp file and is responsible for verification,
 * final rename, and sidecar writes (delegated to [ModelManager.installFromFile]).
 * On all non-success paths the temp file has already been deleted.
 */
sealed class DownloadResult {
    /** Temp file fully written; caller must finalize (verify → rename → activate). */
    data class Success(val tempFile: File) : DownloadResult()

    /** [ModelMetadata.source] is not [ModelSource.RemoteDownload] or its URL is blank. */
    object SourceNotFound : DownloadResult()

    /** The coroutine was cancelled mid-download; temp file cleaned up. */
    object Cancelled : DownloadResult()

    /**
     * All attempts failed or a non-retryable error occurred.
     *
     * [attempt] is 1 when the first attempt failed with a non-retryable error (HTTP 4xx),
     * or 2 when both attempts were exhausted.
     */
    data class Error(val cause: Throwable, val attempt: Int) : DownloadResult()
}

// ---------------------------------------------------------------------------
// Repository
// ---------------------------------------------------------------------------

/**
 * Handles HTTP streaming downloads for ML model binaries.
 *
 * Design rules:
 * - All URLs are read from [ModelMetadata.source]; none are hard-coded here.
 * - Uses [java.net.HttpURLConnection] — no third-party HTTP library required.
 * - Retries **once** on transient failures: [IOException] (network/timeout) or HTTP 5xx.
 * - Does **not** retry on HTTP 4xx (client error) or coroutine cancellation.
 * - [onProgress] is invoked on the IO thread after each buffer write.
 * - The temp file path is owned by [ModelStorage]; it is always deleted before returning
 *   on any non-[DownloadResult.Success] path.
 */
class ModelDownloadRepository(private val storage: ModelStorage) {

    /**
     * Downloads the binary for [meta] to a temporary file.
     *
     * The returned [DownloadResult.Success.tempFile] is inside [ModelStorage]'s managed
     * directory tree. Pass it directly to [ModelManager.installFromFile] to complete the
     * install (SHA-256 check, atomic rename, sidecar writes, activation).
     *
     * @param onProgress called periodically with (bytesDownloaded, totalBytes).
     *   [totalBytes] is -1 when Content-Length is absent from the response.
     */
    suspend fun download(
        meta: ModelMetadata,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val source = meta.source as? ModelSource.RemoteDownload
        if (source == null || source.url.isBlank()) {
            return@withContext DownloadResult.SourceNotFound
        }

        val tempFile = storage.tempFile(meta)
        var lastError: Throwable? = null

        for (attempt in 1..2) {
            // Check for cancellation before (re-)attempting.
            if (!isActive) {
                tempFile.delete()
                return@withContext DownloadResult.Cancelled
            }

            try {
                streamToFile(
                    url          = source.url,
                    dest         = tempFile,
                    expectedBytes = meta.sizeBytes,
                    isCancelled  = { !isActive },
                    onProgress   = onProgress,
                )
                Log.i(TAG, "Downloaded ${meta.id}-${meta.version} (attempt $attempt)")
                return@withContext DownloadResult.Success(tempFile)

            } catch (e: CancellationException) {
                tempFile.delete()
                return@withContext DownloadResult.Cancelled

            } catch (e: HttpStatusException) {
                tempFile.delete()
                if (e.status in 400..499) {
                    // Client error — retrying won't help.
                    Log.e(TAG, "HTTP ${e.status} for ${meta.id} — not retrying")
                    return@withContext DownloadResult.Error(e, attempt)
                }
                // Server error (5xx) — allow one retry.
                Log.w(
                    TAG,
                    "HTTP ${e.status} for ${meta.id} on attempt $attempt" +
                        if (attempt < 2) " — retrying" else " — giving up"
                )
                lastError = e

            } catch (e: IOException) {
                tempFile.delete()
                Log.w(
                    TAG,
                    "IO error for ${meta.id} on attempt $attempt" +
                        if (attempt < 2) " — retrying" else " — giving up: ${e.message}"
                )
                lastError = e
            }
        }

        DownloadResult.Error(
            cause   = lastError ?: IOException("Download failed after 2 attempts"),
            attempt = 2
        )
    }

    // -------------------------------------------------------------------------
    // Internal: raw HTTP stream
    // -------------------------------------------------------------------------

    /**
     * Opens [url], streams the response body into [dest], and invokes [onProgress]
     * after each buffer write.
     *
     * [isCancelled] is polled on every iteration; a `true` result throws
     * [CancellationException] so the outer loop can clean up and return [DownloadResult.Cancelled].
     *
     * @throws HttpStatusException when the server returns a non-2xx status.
     * @throws IOException         on connection, read, or write failure.
     * @throws CancellationException when [isCancelled] returns true mid-stream.
     */
    @Throws(HttpStatusException::class, IOException::class, CancellationException::class)
    private fun streamToFile(
        url: String,
        dest: File,
        expectedBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout    = READ_TIMEOUT
        try {
            conn.connect()
            val status = conn.responseCode
            if (status !in 200..299) throw HttpStatusException(status)

            val total: Long = when {
                conn.contentLengthLong > 0 -> conn.contentLengthLong
                expectedBytes > 0          -> expectedBytes
                else                       -> -1L
            }

            var downloaded = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (isCancelled()) throw CancellationException("Download cancelled")
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }

            Log.d(TAG, "Streamed $downloaded bytes -> ${dest.name}")
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------------------------------------------------------------------
// Internal exception
// ---------------------------------------------------------------------------

/** Thrown when the server returns a non-2xx HTTP status code. */
internal class HttpStatusException(val status: Int) : IOException("HTTP $status")
