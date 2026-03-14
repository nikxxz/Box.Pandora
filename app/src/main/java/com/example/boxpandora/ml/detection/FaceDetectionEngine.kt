package com.example.boxpandora.ml.detection

import android.graphics.Bitmap
import java.io.Closeable

/**
 * Contract for a face detector engine.
 *
 * Each engine encapsulates all logic for one detector architecture:
 *   • input preprocessing (normalisation, channel order, layout)
 *   • running inference via the [ModelRuntime]
 *   • output tensor parsing and NMS
 *
 * Workers and UI code must never interact with engines directly — use
 * [com.example.boxpandora.ml.inference.FaceDetectionService], which resolves the
 * active model and routes to the correct engine.
 *
 * Lifecycle: create once per worker invocation; call [close] in a finally block.
 * Not thread-safe.
 */
interface FaceDetectionEngine : Closeable {

    /**
     * The manifest [com.example.boxpandora.ml.model.ModelMetadata.id] of the backing model.
     * Written into [DetectionResult.detectorId].
     */
    val detectorId: String

    /**
     * The manifest [com.example.boxpandora.ml.model.ModelMetadata.roomVersionKey].
     * Written into [DetectionResult.detectorModelVersion] and DetectedFace DB rows.
     */
    val modelVersionKey: String

    /** Runtime thresholds used by this engine instance. */
    val config: DetectorConfig

    /**
     * Runs inference on [bitmap] and returns filtered, NMS-suppressed detections.
     * The [bitmap] is not recycled by this call.
     */
    fun detect(bitmap: Bitmap): List<DetectionResult>

    /**
     * Produces a 112×112 ArcFace-aligned face crop for [detection].
     *
     * Uses a 3-point affine transform (left eye, right eye, nose) when landmarks are
     * available in [detection]; falls back to a padded bounding-box crop otherwise.
     *
     * The returned [Bitmap] is owned by the caller and must be recycled after embedding.
     */
    fun alignFace(sourceBitmap: Bitmap, detection: DetectionResult): Bitmap =
        FaceAlignmentHelper.align(sourceBitmap, detection)
}
