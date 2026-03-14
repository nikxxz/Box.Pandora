package com.example.boxpandora.ml.manager

import com.example.boxpandora.ml.model.InstalledModel

/** Result returned by [ModelManager.installFromAsset] and [ModelManager.installFromFile]. */
sealed class ModelInstallResult {
    /** Model was successfully installed and is now active for its category. */
    data class Success(val installed: InstalledModel) : ModelInstallResult()

    /** The exact version was already present and active; nothing was copied. */
    object AlreadyInstalled : ModelInstallResult()

    /** The file's SHA-256 did not match the manifest entry. File was discarded. */
    object ChecksumMismatch : ModelInstallResult()

    /** The source asset path or download file was not found. */
    object SourceNotFound : ModelInstallResult()

    /** An unexpected I/O or runtime error occurred. */
    data class Error(val cause: Throwable) : ModelInstallResult()

    /**
     * The file passed checksum verification but failed compatibility checks
     * (e.g. extension mismatch, wrong size, or TFLite interpreter init failure).
     * The file has already been deleted.
     */
    data class InvalidModel(val reason: String) : ModelInstallResult()

    /**
     * The manifest entry is missing required integrity fields for a remote download:
     * [ModelMetadata.sha256] is blank, [ModelMetadata.sizeBytes] is zero, or
     * the URL is absent. The download was not attempted; no existing model was touched.
     */
    data class ManifestIncomplete(val reason: String) : ModelInstallResult()
}
