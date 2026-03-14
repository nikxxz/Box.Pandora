package com.example.boxpandora.ml.model

/**
 * Full description of a model entry from the manifest.
 *
 * [id]          – stable machine identifier, e.g. "mobilenet_v3_scene"
 * [displayName] – human-readable label for settings UI
 * [category]    – which pipeline slot this model fills
 * [version]     – semver string, becomes part of the Room modelVersion column value
 * [format]      – always "tflite" for now; reserved for future ONNX support
 * [source]      – where to get the binary (bundled or remote)
 * [sha256]      – lowercase hex SHA-256 of the raw model file
 * [sizeBytes]   – expected file size; used for download-progress display
 * [inputWidth]  – expected width of the input tensor (pixels)
 * [inputHeight] – expected height of the input tensor (pixels)
 * [outputDim]   – length of the output embedding vector
 *
 * The Room [modelVersion] column stores "[category.id]:[id]-[version]",
 * e.g. "scene_embedding:mobilenet_v3_scene-1.0.0".
 */
data class ModelMetadata(
    val id: String,
    val displayName: String,
    val category: ModelCategory,
    val version: String,
    val format: String = "tflite",
    val source: ModelSource,
    val sha256: String,
    val sizeBytes: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputDim: Int,
    /** True when this is the preferred model for its category and should be selected
     *  automatically when no other active model is present. */
    val isDefault: Boolean = false,
    /** True when this model is not required for core operation (e.g. an alternative detector).
     *  Optional models are not downloaded automatically; the user must opt in. */
    val isOptional: Boolean = false,
    /** Reflects the manifest declaration of whether this model is enabled.
     *  Actual runtime preference is stored in AiSettings and may override this value. */
    val isEnabledByUser: Boolean = true
) {
    /** The string written into Room's model_version column. */
    val roomVersionKey: String get() = "${category.id}:$id-$version"
}
