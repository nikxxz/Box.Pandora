package com.example.boxpandora.ml.model

import org.json.JSONObject

/**
 * Install provenance record written as `install_meta.json` inside each model's version
 * directory immediately after a successful install.
 *
 * Provides a persistent, human-readable audit trail of how and when each model arrived
 * on the device. Read back via [ModelStorage.readMetadata].
 *
 * JSON layout (pretty-printed):
 * ```json
 * {
 *   "id":           "mobilenet_v3_scene",
 *   "version":      "1.0.0",
 *   "category":     "scene_embedding",
 *   "format":       "tflite",
 *   "installedAt":  1741234567890,
 *   "sourceType":   "bundled_asset",
 *   "sourceDetail": "models/scene/mobilenet_v3_scene.tflite",
 *   "sha256":       "",
 *   "verifiedAt":   1741234567890,
 *   "sizeBytes":    12345678
 * }
 * ```
 */
data class ModelInstallMeta(
    /** Stable machine identifier matching [ModelMetadata.id]. */
    val id: String,
    /** Semver string matching [ModelMetadata.version]. */
    val version: String,
    /** Category id string matching [ModelCategory.id]. */
    val category: String,
    /** Format string, e.g. "tflite". */
    val format: String,
    /** Epoch milliseconds when the binary was written to its final path. */
    val installedAt: Long,
    /** One of: "bundled_asset", "remote_download", "local_file". */
    val sourceType: String,
    /** Asset path, URL, or absolute device path depending on [sourceType]. */
    val sourceDetail: String,
    /** SHA-256 hex used during the integrity check; blank when check was skipped. */
    val sha256: String,
    /** Epoch milliseconds when SHA-256 was last verified. */
    val verifiedAt: Long,
    /** Actual file size in bytes after install. */
    val sizeBytes: Long
) {

    /** Serialises this record to a pretty-printed JSON string. */
    fun toJson(): String = JSONObject()
        .put("id",           id)
        .put("version",      version)
        .put("category",     category)
        .put("format",       format)
        .put("installedAt",  installedAt)
        .put("sourceType",   sourceType)
        .put("sourceDetail", sourceDetail)
        .put("sha256",       sha256)
        .put("verifiedAt",   verifiedAt)
        .put("sizeBytes",    sizeBytes)
        .toString(2)

    companion object {
        /** Parses a [ModelInstallMeta] from the JSON produced by [toJson]. */
        fun fromJson(json: String): ModelInstallMeta {
            val o = JSONObject(json)
            return ModelInstallMeta(
                id           = o.getString("id"),
                version      = o.getString("version"),
                category     = o.getString("category"),
                format       = o.optString("format", "tflite"),
                installedAt  = o.getLong("installedAt"),
                sourceType   = o.getString("sourceType"),
                sourceDetail = o.optString("sourceDetail", ""),
                sha256       = o.optString("sha256", ""),
                verifiedAt   = o.optLong("verifiedAt", 0L),
                sizeBytes    = o.optLong("sizeBytes", 0L)
            )
        }
    }
}
