package com.example.boxpandora.ml.storage

import android.content.Context
import android.util.Log
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.model.ModelFormat
import com.example.boxpandora.ml.model.ModelInstallMeta
import com.example.boxpandora.ml.model.ModelMetadata
import java.io.File

private const val TAG            = "ModelStorage"
private const val METADATA_FILE  = "install_meta.json"
private const val CHECKSUM_FILE  = "checksum.json"

/**
 * Manages the on-device file-system layout for ML model binaries and their sidecar files.
 *
 * Storage tree (all paths under [Context.getFilesDir]):
 * ```
 *   ml_models/
 *     {category.id}/
 *       {model.id}/
 *         {model.version}/
 *           model.<ext>           <- ready-to-load binary
 *           install_meta.json     <- install provenance (source, timestamp, size)
 *           checksum.json         <- SHA-256 state + verification timestamp
 *           model.<ext>.tmp       <- transient during install (never persisted)
 * ```
 *
 * This class is purely concerned with path resolution and file-system I/O.
 * Active model selection and crash-loop state live in ModelManager (SharedPreferences).
 */
class ModelStorage(context: Context) {

    internal val root: File = File(context.filesDir, "ml_models").also { it.mkdirs() }

    // -- Directory helpers -----------------------------------------------------

    /** `ml_models/{category.id}/` */
    fun categoryDir(category: ModelCategory): File =
        File(root, category.id).also { it.mkdirs() }

    /** `ml_models/{category.id}/{model.id}/` */
    fun modelIdDir(meta: ModelMetadata): File =
        File(categoryDir(meta.category), meta.id).also { it.mkdirs() }

    /** `ml_models/{category.id}/{model.id}/{model.version}/` */
    fun versionDir(meta: ModelMetadata): File =
        File(modelIdDir(meta), meta.version).also { it.mkdirs() }

    // -- File path resolution --------------------------------------------------

    /**
     * Canonical path for the ready-to-load model binary.
     * The file may not exist yet — check [isInstalled] before loading.
     */
    fun modelFile(meta: ModelMetadata): File =
        File(versionDir(meta), "model.${resolveFormat(meta.format).extension}")

    /**
     * Transient path used during installation.
     * This file must be renamed or deleted by the caller; it is never returned
     * as a ready-to-load path.
     */
    fun tempFile(meta: ModelMetadata): File =
        File(versionDir(meta), "model.${resolveFormat(meta.format).extension}.tmp")

    /** Path for the `install_meta.json` sidecar. */
    fun metadataFile(meta: ModelMetadata): File = File(versionDir(meta), METADATA_FILE)

    /** Path for the `checksum.json` sidecar. */
    fun checksumFile(meta: ModelMetadata): File = File(versionDir(meta), CHECKSUM_FILE)

    // -- Inspection ------------------------------------------------------------

    /** Returns true if the final model binary for [meta] is present on disk. */
    fun isInstalled(meta: ModelMetadata): Boolean = modelFile(meta).exists()

    /**
     * Lists all version directories (depth 2 below the category dir) present for [category],
     * regardless of whether their binaries are fully installed.
     */
    fun listVersionDirs(category: ModelCategory): List<File> {
        val catDir = File(root, category.id)
        if (!catDir.exists()) return emptyList()
        return catDir.walkTopDown()
            .filter { it.isDirectory && fileDepthBelow(it, catDir) == 2 }
            .toList()
    }

    // -- Metadata / checksum persistence --------------------------------------

    /**
     * Reads and deserialises `install_meta.json` for [meta], or returns null if the
     * file is absent or cannot be parsed.
     */
    fun readMetadata(meta: ModelMetadata): ModelInstallMeta? {
        val file = metadataFile(meta)
        if (!file.exists()) return null
        return try {
            ModelInstallMeta.fromJson(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $METADATA_FILE for ${meta.id}: ${e.message}")
            null
        }
    }

    /**
     * Serialises [installMeta] and writes it to `install_meta.json` in the version directory.
     * Silently logs on I/O error — metadata is informational; failure must not block activation.
     */
    fun writeMetadata(meta: ModelMetadata, installMeta: ModelInstallMeta) {
        try {
            metadataFile(meta).writeText(installMeta.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write $METADATA_FILE for ${meta.id}: ${e.message}")
        }
    }

    /**
     * Writes `checksum.json` recording the SHA-256 verification outcome.
     *
     * @param sha256      Hex SHA-256 that was checked (blank = check was skipped).
     * @param passed      True when verify succeeded or was skipped (blank sha256).
     * @param verifiedAt  Epoch milliseconds of the verification.
     */
    fun writeChecksumState(
        meta: ModelMetadata,
        sha256: String,
        passed: Boolean,
        verifiedAt: Long = System.currentTimeMillis()
    ) {
        val json = """{"sha256":"$sha256","verifiedAt":$verifiedAt,"verificationPassed":$passed}"""
        try {
            checksumFile(meta).writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write $CHECKSUM_FILE for ${meta.id}: ${e.message}")
        }
    }

    // -- Deletion --------------------------------------------------------------

    /**
     * Deletes the version directory for [meta] (binary + all sidecars), then prunes
     * empty parent directories up to the category level.
     */
    fun deleteModel(meta: ModelMetadata) {
        val vDir = versionDir(meta)
        if (vDir.exists()) {
            vDir.deleteRecursively()
            Log.d(TAG, "Deleted version dir: ${vDir.path}")
        }
        // Prune empty id-level and category-level dirs
        val idDir  = File(File(root, meta.category.id), meta.id)
        val catDir = File(root, meta.category.id)
        if (idDir.exists()  && idDir.listFiles().isNullOrEmpty())  idDir.delete()
        if (catDir.exists() && catDir.listFiles().isNullOrEmpty()) catDir.delete()
    }

    /** Deletes all installed versions for [category]. */
    fun deleteCategory(category: ModelCategory) {
        categoryDir(category).deleteRecursively()
    }

    /** Returns the total bytes consumed by all files under [root]. */
    fun totalStorageUsed(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // -- Internal --------------------------------------------------------------

    private fun resolveFormat(formatString: String): ModelFormat =
        ModelFormat.entries.firstOrNull { it.name.equals(formatString, ignoreCase = true) }
            ?: ModelFormat.TFLITE

    private fun fileDepthBelow(file: File, base: File): Int {
        var depth = 0
        var current: File? = file
        while (current != null && current != base) {
            depth++
            current = current.parentFile
        }
        return depth
    }
}