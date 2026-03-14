package com.example.boxpandora.ml.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Shared face-alignment utilities used by all [FaceDetectionEngine] implementations.
 *
 * ArcFace 5-point template positions in 112×112 pixel space:
 *   index 0 — left-of-image eye  (landmark lm0, right eye from subject's perspective)
 *   index 1 — right-of-image eye (landmark lm1, left  eye from subject's perspective)
 *   index 2 — nose tip           (landmark lm2)
 */
internal object FaceAlignmentHelper {

    private val ARCFACE_TEMPLATE_3PT = floatArrayOf(
        38.2946f, 51.6963f,   // lm0: left-of-image eye
        73.5318f, 51.5014f,   // lm1: right-of-image eye
        56.0252f, 71.7366f    // lm2: nose tip
    )

    /**
     * Returns a 112×112 [Bitmap] aligned to the ArcFace template.
     *
     * Uses a 3-point affine transform when [detection] has at least 3 landmarks;
     * falls back to a padded bounding-box crop otherwise.
     */
    fun align(source: Bitmap, detection: DetectionResult): Bitmap =
        if (detection.landmarks.size >= 3) landmarkCrop(source, detection)
        else paddedBoxCrop(source, detection)

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun landmarkCrop(src: Bitmap, detection: DetectionResult): Bitmap {
        val W  = src.width.toFloat(); val H = src.height.toFloat()
        val lm = detection.landmarks
        val srcPts = floatArrayOf(
            lm[0].first * W, lm[0].second * H,
            lm[1].first * W, lm[1].second * H,
            lm[2].first * W, lm[2].second * H
        )
        val matrix = Matrix()
        matrix.setPolyToPoly(srcPts, 0, ARCFACE_TEMPLATE_3PT, 0, 3)
        val aligned = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        Canvas(aligned).drawBitmap(
            src, matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return aligned
    }

    private fun paddedBoxCrop(src: Bitmap, detection: DetectionResult): Bitmap {
        val W = src.width; val H = src.height; val pad = 0.20f
        val l = ((detection.leftNorm   - pad * detection.widthNorm)  * W).toInt().coerceIn(0, W)
        val t = ((detection.topNorm    - pad * detection.heightNorm) * H).toInt().coerceIn(0, H)
        val r = ((detection.rightNorm  + pad * detection.widthNorm)  * W).toInt().coerceIn(0, W)
        val b = ((detection.bottomNorm + pad * detection.heightNorm) * H).toInt().coerceIn(0, H)
        val cW = (r - l).coerceAtLeast(1); val cH = (b - t).coerceAtLeast(1)
        val crop   = Bitmap.createBitmap(src, l, t, cW, cH)
        val scaled = Bitmap.createScaledBitmap(crop, 112, 112, true)
        if (crop !== src) crop.recycle()
        return scaled
    }
}
