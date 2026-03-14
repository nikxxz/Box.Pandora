package com.example.boxpandora.ml.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.boxpandora.ml.model.InstalledModel
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.model.ModelInstallMeta
import com.example.boxpandora.ml.model.ModelManifest
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ml.model.ModelSource
import com.example.boxpandora.ml.storage.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG                = "ModelManager"
private const val PREFS_NAME         = "model_manager_active"
private const val FAIL_COUNT_PREFIX  = "fail_count_"
private const val MAX_MODEL_FAILURES = 3

/**
 * Manages the full ML model lifecycle: listing, installing, activating, and deleting models.
 *
 * Storage layout (under [Context.getFilesDir]):
 * ```
 *   ml_models/{category.id}/{model.id}/{model.version}/
 *     model.<ext>           <- ready-to-load binary
 *     install_meta.json     <- install provenance (source, timestamp, size)
 *     checksum.json         <- SHA-256 state + verification timestamp
 *     model.<ext>.tmp       <- transient during install (always deleted)
 * ```
 *
 * Active model per category is persisted in SharedPreferences:
 *   key = category.id  ->  value = "{modelId}-{version}"
 * This pointer survives process death and app restarts.
 *
 * Install safety guarantees:
 *   - The active pointer is only written after the binary is on disk and SHA-256 verified.
 *   - On any failure (checksum, I/O, network) the temp file is deleted immediately.
 *   - The previous active model is never touched on a failed install attempt.
 */
class ModelManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** File-system layout delegate — all path resolution goes through here. */
    internal val storage = ModelStorage(context)

    /** HTTP download delegate — handles streaming, retry, and cancellation. */
    private val downloader = ModelDownloadRepository(storage)

    /** Full manifest loaded at construction time. */
    val manifest: ModelManifest = ModelManifest.load(context)

    // -- Active version tag helpers -------------------------------------------

    /** Builds the stable in-prefs key from a metadata entry. */
    private fun versionTag(meta: ModelMetadata): String = "${meta.id}-${meta.version}"

    private fun getActiveVersionTag(category: ModelCategory): String? =
        prefs.getString(category.id, null)

    private fun setActiveVersionTag(category: ModelCategory, tag: String) {
        prefs.edit().putString(category.id, tag).apply()
    }

    private fun clearActiveVersionTag(category: ModelCategory) {
        prefs.edit().remove(category.id).apply()
    }

    // -- Public: inspection ---------------------------------------------------

    /**
     * Reads the manifest and returns every entry that has a binary present on disk,
     * with [InstalledModel.isActive] set correctly.
     */
    fun getInstalledModels(): List<InstalledModel> =
        manifest.models.mapNotNull { meta ->
            val file = storage.modelFile(meta)
            if (file.exists())
                InstalledModel(meta, file, isActive = getActiveVersionTag(meta.category) == versionTag(meta))
            else null
        }

    /**
     * Returns the currently active [InstalledModel] for [category], or null if no
     * model is installed or activated for that category.
     */
    fun getActiveModel(category: ModelCategory): InstalledModel? {
        val tag  = getActiveVersionTag(category) ?: return null
        val meta = manifest.models.firstOrNull { it.category == category && versionTag(it) == tag }
            ?: return null
        val file = storage.modelFile(meta)
        return if (file.exists()) InstalledModel(meta, file, isActive = true) else null
    }

    // -- Public: explicit activation ------------------------------------------

    /**
     * Makes [meta] the active model for its category and persists the choice.
     *
     * Returns false (and logs a warning) if the binary is not on disk — you cannot
     * activate a model that has not been installed.
     */
    fun activate(meta: ModelMetadata): Boolean {
        val file = storage.modelFile(meta)
        if (!file.exists()) {
            Log.w(TAG, "activate: binary not found for ${meta.id}-${meta.version} — ignoring")
            return false
        }
        setActiveVersionTag(meta.category, versionTag(meta))
        Log.i(TAG, "Activated ${meta.id}-${meta.version} for ${meta.category.id}")
        return true
    }

    /**
     * Clears the active model pointer for [category].
     *
     * The previously active binary stays on disk and can be re-activated with [activate].
     * Workers and inference services will return null from [getActiveModel] until a new
     * model is activated.
     */
    fun deactivate(category: ModelCategory) {
        clearActiveVersionTag(category)
        Log.i(TAG, "Deactivated model for ${category.id}")
    }

    // -- Public: install from bundled asset -----------------------------------

    /**
     * Installs [meta] by copying it from the APK assets directory.
     *
     * Returns [ModelInstallResult.AlreadyInstalled] if the binary is already present,
     * active, and passes SHA-256 verification — avoiding redundant copies at startup.
     */
    suspend fun installFromAsset(meta: ModelMetadata): ModelInstallResult =
        withContext(Dispatchers.IO) {
            val source = meta.source as? ModelSource.BundledAsset
                ?: return@withContext ModelInstallResult.Error(
                    IllegalArgumentException("${meta.id} source is not BundledAsset")
                )

            val finalFile = storage.modelFile(meta)
            if (isAlreadyInstalled(meta, finalFile)) {
                return@withContext ModelInstallResult.AlreadyInstalled
            }

            val tempFile = storage.tempFile(meta)
            try {
                context.assets.open(source.assetPath).use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                tempFile.delete()
                Log.e(TAG, "Asset not found: ${source.assetPath}", e)
                return@withContext ModelInstallResult.SourceNotFound
            }

            finalize(meta, tempFile, finalFile, sourceType = "bundled_asset", sourceDetail = source.assetPath)
        }

    // -- Public: install from local file --------------------------------------

    /**
     * Installs [meta] from a pre-existing [sourceFile] on device storage (e.g. a sideloaded
     * development binary or a file fetched by an external download manager).
     *
     * [sourceFile] is deleted after a successful install.
     */
    suspend fun installFromFile(meta: ModelMetadata, sourceFile: File): ModelInstallResult =
        withContext(Dispatchers.IO) {
            if (!sourceFile.exists()) return@withContext ModelInstallResult.SourceNotFound

            val finalFile = storage.modelFile(meta)
            val tempFile  = storage.tempFile(meta)
            try {
                sourceFile.copyTo(tempFile, overwrite = true)
            } catch (e: Exception) {
                tempFile.delete()
                return@withContext ModelInstallResult.Error(e)
            }

            val result = finalize(meta, tempFile, finalFile, sourceType = "local_file", sourceDetail = sourceFile.absolutePath)
            if (result is ModelInstallResult.Success) sourceFile.delete()
            result
        }

    // -- Public: install from URL ---------------------------------------------

    /**
     * Downloads and installs [meta] from its [ModelSource.RemoteDownload] URL.
     *
     * Download runs on [Dispatchers.IO]. [onProgress] is invoked periodically with
     * (bytesDownloaded, totalBytes); totalBytes is -1 when Content-Length is unknown.
     *
     * Returns [ModelInstallResult.SourceNotFound] when the URL is blank.
     * Returns [ModelInstallResult.AlreadyInstalled] when the model is already active
     * and passes integrity verification.
     */
    suspend fun installFromUrl(
        meta: ModelMetadata,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ModelInstallResult {
        val source = meta.source as? ModelSource.RemoteDownload
            ?: return ModelInstallResult.Error(
                IllegalArgumentException("${meta.id} source is not RemoteDownload")
            )
        if (source.url.isBlank()) return ModelInstallResult.SourceNotFound

        val finalFile = storage.modelFile(meta)
        if (isAlreadyInstalled(meta, finalFile)) return ModelInstallResult.AlreadyInstalled

        return when (val dl = downloader.download(meta, onProgress)) {
            is DownloadResult.Success      -> withContext(Dispatchers.IO) {
                finalize(
                    meta        = meta,
                    tempFile    = dl.tempFile,
                    finalFile   = finalFile,
                    sourceType  = "remote_download",
                    sourceDetail = source.url,
                )
            }
            DownloadResult.SourceNotFound  -> ModelInstallResult.SourceNotFound
            DownloadResult.Cancelled       -> ModelInstallResult.Error(
                java.util.concurrent.CancellationException("Download cancelled")
            )
            is DownloadResult.Error        -> {
                Log.e(TAG, "Download failed for ${meta.id} after ${dl.attempt} attempt(s)", dl.cause)
                ModelInstallResult.Error(dl.cause)
            }
        }
    }

    // -- Public: delete -------------------------------------------------------

    /**
     * Deletes the binary and all sidecar files for [meta].
     * Clears the active pointer for the category if [meta] was the active model.
     */
    suspend fun delete(meta: ModelMetadata): Unit = withContext(Dispatchers.IO) {
        storage.deleteModel(meta)
        if (getActiveVersionTag(meta.category) == versionTag(meta)) {
            clearActiveVersionTag(meta.category)
        }
        Log.i(TAG, "Deleted ${meta.id}-${meta.version}")
    }

    // -- Integrity & fallback -------------------------------------------------

    /**
     * Returns true when the currently active model for [category] passes SHA-256 verification
     * (or has no sha256 to check, or is not installed).
     */
    fun verifyActiveModel(category: ModelCategory): Boolean {
        val tag  = getActiveVersionTag(category) ?: return true
        val meta = manifest.models.firstOrNull { it.category == category && versionTag(it) == tag }
            ?: return true
        val file = storage.modelFile(meta)
        if (!file.exists()) return true
        return ModelIntegrityVerifier.verify(file, meta.sha256)
    }

    /**
     * Returns the active [InstalledModel] for [category], falling back to any other installed
     * model that passes integrity verification when the active file is corrupt or missing.
     *
     * The fallback is promoted to active before being returned so that subsequent calls to
     * [getActiveModel] find it immediately without re-scanning.
     */
    fun getActiveModelWithFallback(category: ModelCategory): InstalledModel? {
        val tag        = getActiveVersionTag(category)
        val activeMeta = manifest.models.firstOrNull { it.category == category && versionTag(it) == tag }
        val activeFile = activeMeta?.let { storage.modelFile(it) }

        if (activeFile != null && activeFile.exists()) {
            if (ModelIntegrityVerifier.verify(activeFile, activeMeta!!.sha256)) {
                return InstalledModel(activeMeta, activeFile, isActive = true)
            }
            Log.e(TAG, "Active model ${activeMeta.id}-${activeMeta.version} failed integrity — clearing")
            clearActiveVersionTag(category)
        }

        val fallback = manifest.models
            .filter { it.category == category && versionTag(it) != tag }
            .mapNotNull { meta ->
                val f = storage.modelFile(meta)
                if (f.exists() && ModelIntegrityVerifier.verify(f, meta.sha256))
                    InstalledModel(meta, f, isActive = false)
                else null
            }
            .firstOrNull()

        if (fallback != null) {
            Log.w(TAG, "Falling back to ${fallback.metadata.id} for ${category.id}")
            setActiveVersionTag(category, versionTag(fallback.metadata))
            return fallback.copy(isActive = true)
        }

        Log.e(TAG, "No valid fallback model for ${category.id}")
        return null
    }

    // -- Crash loop guard -----------------------------------------------------

    private fun failCountKey(category: ModelCategory) = "$FAIL_COUNT_PREFIX${category.id}"

    /** Records one inference failure for [category]; returns the new cumulative count. */
    fun recordModelFailure(category: ModelCategory): Int {
        val count = prefs.getInt(failCountKey(category), 0) + 1
        prefs.edit().putInt(failCountKey(category), count).apply()
        Log.w(TAG, "Model failure recorded for ${category.id}: $count / $MAX_MODEL_FAILURES")
        return count
    }

    /** Clears the failure counter after a successful inference run. */
    fun clearModelFailures(category: ModelCategory) {
        prefs.edit().remove(failCountKey(category)).apply()
    }

    /** Returns the current failure count for [category]. */
    fun getModelFailureCount(category: ModelCategory): Int =
        prefs.getInt(failCountKey(category), 0)

    /** Returns true once the failure count reaches [MAX_MODEL_FAILURES]. */
    fun hasTooManyFailures(category: ModelCategory): Boolean =
        prefs.getInt(failCountKey(category), 0) >= MAX_MODEL_FAILURES

    // -- Internal: install helpers --------------------------------------------

    /**
     * Returns true when [finalFile] is already present, active, and passes the SHA-256 check.
     * Used at the top of each install function to skip redundant work.
     */
    private fun isAlreadyInstalled(meta: ModelMetadata, finalFile: File): Boolean =
        finalFile.exists() &&
        getActiveVersionTag(meta.category) == versionTag(meta) &&
        ModelIntegrityVerifier.verify(finalFile, meta.sha256)

    /**
     * Core finalization shared by all install paths:
     *   1. SHA-256 verify the temp file — on failure: delete temp, return ChecksumMismatch.
     *   2. Rename temp -> final (copy-fallback on cross-volume FS) — on failure: delete temp.
     *   3. Compatibility checks: extension, size, TFLite init sanity.
     *   4. Write `install_meta.json` and `checksum.json`.
     *   5. Persist active pointer in SharedPreferences.
     *
     * The previous active model is not touched if this function returns anything other
     * than [ModelInstallResult.Success].
     */
    private fun finalize(
        meta: ModelMetadata,
        tempFile: File,
        finalFile: File,
        sourceType: String,
        sourceDetail: String
    ): ModelInstallResult {
        // Step 1: SHA-256 verification
        val sha256Passed = ModelIntegrityVerifier.verify(tempFile, meta.sha256)
        if (!sha256Passed) {
            tempFile.delete()
            Log.e(TAG, "Checksum mismatch for ${meta.id}-${meta.version}")
            return ModelInstallResult.ChecksumMismatch
        }

        // Step 2: Atomic rename with copy fallback
        val moved = tempFile.renameTo(finalFile)
        if (!moved) {
            try {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            } catch (e: Exception) {
                tempFile.delete()
                finalFile.delete()
                return ModelInstallResult.Error(e)
            }
        }

        // Step 3: Compatibility checks (extension, size, TFLite init)
        val compatFailure = ModelCompatibilityChecker.check(meta, finalFile)
        if (compatFailure != null) {
            finalFile.delete()
            Log.e(TAG, "Compatibility check failed for ${meta.id}-${meta.version}: $compatFailure")
            return ModelInstallResult.InvalidModel(compatFailure)
        }

        // Steps 4 + 5: Persist metadata and activate
        writeInstallSidecars(meta, sourceType, sourceDetail)
        Log.i(TAG, "Installed ${meta.id}-${meta.version} -> ${finalFile.absolutePath}")
        return ModelInstallResult.Success(InstalledModel(meta, finalFile, isActive = true))
    }

    /**
     * Writes `install_meta.json`, `checksum.json`, and persists the active pointer.
     * All three writes are fire-and-forget — I/O errors are logged but do not throw.
     */
    private fun writeInstallSidecars(meta: ModelMetadata, sourceType: String, sourceDetail: String) {
        val now          = System.currentTimeMillis()
        val actualSize   = storage.modelFile(meta).length()
        val installMeta  = ModelInstallMeta(
            id           = meta.id,
            version      = meta.version,
            category     = meta.category.id,
            format       = meta.format,
            installedAt  = now,
            sourceType   = sourceType,
            sourceDetail = sourceDetail,
            sha256       = meta.sha256,
            verifiedAt   = now,
            sizeBytes    = actualSize
        )
        storage.writeMetadata(meta, installMeta)
        storage.writeChecksumState(meta, sha256 = meta.sha256, passed = true, verifiedAt = now)
        setActiveVersionTag(meta.category, versionTag(meta))
    }

}