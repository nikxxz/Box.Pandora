package com.example.boxpandora.ml.model

import android.content.Context
import android.util.Log

private const val TAG = "ModelManifest"

/**
 * Top-level manifest that lists all models the app knows about.
 *
 * **Source of truth at runtime:** [load] parses [ManifestParser.MANIFEST_ASSET_PATH] from the
 * APK assets and returns a live [ModelManifest].  The hardcoded [default] companion property
 * serves only as a compile-time fallback for unit tests and edge cases where the asset is
 * unavailable; it must not be referenced directly from production workers or services.
 *
 * All call sites should use [load]:
 * ```kotlin
 * val manifest = ModelManifest.load(context)
 * val meta = manifest.defaultForCategory(ModelCategory.SCENE_EMBEDDING)
 * ```
 */
data class ModelManifest(val models: List<ModelMetadata>) {

    companion object {

        // ── Runtime loader ────────────────────────────────────────────────────

        /**
         * Parses the JSON manifest from [context]'s assets and returns the result.
         *
         * Falls back to [default] (with a logged error) if the asset is missing or
         * the JSON is malformed, so the app can still function during development before
         * the JSON is populated.
         */
        fun load(context: Context): ModelManifest {
            return try {
                ManifestParser.parse(context.assets)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $MANIFEST_ASSET_PATH — falling back to built-in manifest", e)
                default
            }
        }

        // ── Compile-time fallback (not for production use) ────────────────────

        /**
         * Hardcoded manifest used as a fallback when [load] cannot parse the JSON asset.
         *
         * ⚠ Do not reference this from workers, services, or UI.  Use [load] instead.
         */
        val default: ModelManifest = ModelManifest(
            models = listOf(
                // ── Scene embedding ───────────────────────────────────────────────
                ModelMetadata(
                    id              = "mobilenet_v3_scene",
                    displayName     = "MobileNet V3 Scene",
                    category        = ModelCategory.SCENE_EMBEDDING,
                    version         = "1.0.0",
                    format          = "tflite",
                    source          = ModelSource.RemoteDownload(
                        "https://media.githubusercontent.com/media/nikxxz/Pandora_assets/master/models/mobilenet_v3_feature_vector_fp16.tflite"
                    ),
                    sha256          = "",
                    sizeBytes       = 0L,
                    inputWidth      = 224,
                    inputHeight     = 224,
                    outputDim       = 1280,
                    isDefault       = true,
                    isOptional      = false,
                    isEnabledByUser = true
                ),
                // ── Face embedding ────────────────────────────────────────────────
                /**
                 * ArcFace ResNet-100 float16 TFLite — default face embedding model.
                 * Smaller and faster than float32; recommended for on-device inference.
                 * Input:  [1, 112, 112, 3] float32.  Output: 512-d L2-normalised vector.
                 */
                ModelMetadata(
                    id              = "arcface_resnet100_fp16",
                    displayName     = "ArcFace ResNet-100 (float16)",
                    category        = ModelCategory.FACE_EMBEDDING,
                    version         = "1.0.0",
                    format          = "tflite",
                    source          = ModelSource.RemoteDownload(
                        "https://media.githubusercontent.com/media/nikxxz/Pandora_assets/master/models/arcfaceresnet100-8_float16.tflite"
                    ),
                    sha256          = "",
                    sizeBytes       = 0L,
                    inputWidth      = 112,
                    inputHeight     = 112,
                    outputDim       = 512,
                    isDefault       = true,
                    isOptional      = false,
                    isEnabledByUser = true
                ),
                /**
                 * ArcFace ResNet-100 float32 TFLite — optional higher-precision alternative.
                 * Larger model; useful when float16 quantisation causes accuracy loss.
                 * Input:  [1, 112, 112, 3] float32.  Output: 512-d L2-normalised vector.
                 */
                ModelMetadata(
                    id              = "arcface_resnet100_fp32",
                    displayName     = "ArcFace ResNet-100 (float32)",
                    category        = ModelCategory.FACE_EMBEDDING,
                    version         = "1.0.0",
                    format          = "tflite",
                    source          = ModelSource.RemoteDownload(
                        "https://media.githubusercontent.com/media/nikxxz/Pandora_assets/master/models/arcfaceresnet100-8_float32.tflite"
                    ),
                    sha256          = "",
                    sizeBytes       = 0L,
                    inputWidth      = 112,
                    inputHeight     = 112,
                    outputDim       = 512,
                    isDefault       = false,
                    isOptional      = true,
                    isEnabledByUser = false
                ),
                /**
                 * ArcFace ResNet-100 ONNX — optional alternative for ONNX Runtime backend.
                 * Input:  [1, 3, 112, 112] float32 (NCHW).  Output: 512-d embedding.
                 */
                ModelMetadata(
                    id              = "arcface_resnet100_onnx",
                    displayName     = "ArcFace ResNet-100 (ONNX)",
                    category        = ModelCategory.FACE_EMBEDDING,
                    version         = "1.0.0",
                    format          = "onnx",
                    source          = ModelSource.RemoteDownload(
                        "https://media.githubusercontent.com/media/nikxxz/Pandora_assets/master/models/arcfaceresnet100-8.onnx"
                    ),
                    sha256          = "",
                    sizeBytes       = 0L,
                    inputWidth      = 112,
                    inputHeight     = 112,
                    outputDim       = 512,
                    isDefault       = false,
                    isOptional      = true,
                    isEnabledByUser = false
                ),
                // ── Face detection ────────────────────────────────────────────────
                /**
                 * YuNet face detector — default ONNX detector.
                 * Input:  [1, 3, 320, 320] float32, RGB, pixel values [0, 255] (NCHW).
                 * Output: [1, N, 15] float32 — score + bbox(4) + 5 landmarks(10).
                 */
                ModelMetadata(
                    id              = "yunet_face_detection",
                    displayName     = "YuNet Face Detector",
                    category        = ModelCategory.FACE_DETECTION,
                    version         = "2023.03",
                    format          = "onnx",
                    source          = ModelSource.RemoteDownload(
                        "https://media.githubusercontent.com/media/nikxxz/Pandora_assets/master/models/yunet.onnx"
                    ),
                    sha256          = "",
                    sizeBytes       = 0L,
                    inputWidth      = 320,
                    inputHeight     = 320,
                    outputDim       = 15,
                    isDefault       = true,
                    isOptional      = false,
                    isEnabledByUser = true
                ),
                /**
                 * SCRFD 10G face detector — optional high-accuracy ONNX detector.
                 * Higher accuracy than YuNet; larger model size.
                 * Input:  [1, 3, 640, 640] float32, RGB, normalised [0, 1].
                 * Output: bounding boxes + 5-point landmarks per detected face.
                 */
                ModelMetadata(
                    id              = "scrfd_10g_face_detection",
                    displayName     = "SCRFD 10G Face Detector",
                    category        = ModelCategory.FACE_DETECTION,
                    version         = "1.0.0",
                    format          = "onnx",
                    source          = ModelSource.RemoteDownload(
                        "https://media.githubusercontent.com/media/nikxxz/Pandora_assets/master/models/scrfd_10g_bnkps.onnx"
                    ),
                    sha256          = "",
                    sizeBytes       = 0L,
                    inputWidth      = 640,
                    inputHeight     = 640,
                    outputDim       = 0,
                    isDefault       = false,
                    isOptional      = true,
                    isEnabledByUser = false
                )
            )
        )

        // ── Lookup helpers ────────────────────────────────────────────────────

        /**
         * Returns the default model for [category]:
         *   1. First entry with [ModelMetadata.isDefault] == true for the category, or
         *   2. First entry for the category (regardless of isDefault), or
         *   3. null if no entry exists for the category.
         */
        fun ModelManifest.defaultForCategory(category: ModelCategory): ModelMetadata? =
            models.firstOrNull { it.category == category && it.isDefault }
                ?: models.firstOrNull { it.category == category }
    }
}

