package com.example.boxpandora.ml.detection

import android.graphics.Bitmap
import android.util.Log
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ml.runtime.ModelRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

private const val TAG = "YunetDetectionEngine"

/**
 * Face detector engine for the YuNet ONNX model.
 *
 * Input format:  NCHW [1, 3, H, W], float32, RGB channel order, pixel values [0, 255].
 * Output format: single tensor [1, N, 15].
 *   Row layout per detection: [score, x_c, y_c, w, h, lm0x, lm0y, …, lm4x, lm4y]
 *   All bbox and landmark values are normalised to the input tensor's spatial dimensions.
 *
 * This class owns the [runtime] and closes it in [close].
 */
class YunetDetectionEngine(
    private val runtime: ModelRuntime,
    private val meta: ModelMetadata,
    override val config: DetectorConfig = DetectorConfig.DEFAULT
) : FaceDetectionEngine {

    override val detectorId: String       = meta.id
    override val modelVersionKey: String  = meta.roomVersionKey

    override fun detect(bitmap: Bitmap): List<DetectionResult> {
        val inputBuffer = buildInputBuffer(bitmap, meta.inputWidth, meta.inputHeight)
        val outputs     = runtime.run(inputBuffer)

        val flat  = outputs.getOrElse(0) { return emptyList() }
        val shape = runtime.getOutputShape(0)   // [1, N, 15]
        if (shape.size < 3 || flat.isEmpty()) {
            Log.w(TAG, "Unexpected output shape: ${shape.toList()}")
            return emptyList()
        }

        val n          = shape[1]
        val valPerDet  = shape[2]   // 15
        val srcW       = bitmap.width.toFloat()
        val srcH       = bitmap.height.toFloat()
        val inW        = meta.inputWidth.toFloat()
        val inH        = meta.inputHeight.toFloat()

        val candidates = mutableListOf<DetectionResult>()
        for (i in 0 until n) {
            val base  = i * valPerDet
            val score = flat[base]
            if (score < config.confidenceThreshold) continue

            val xc = flat[base + 1]; val yc = flat[base + 2]
            val bw = flat[base + 3]; val bh = flat[base + 4]

            // YuNet outputs pixel coordinates relative to the input tensor size;
            // divide by input dimensions to get normalised [0, 1] values.
            val left   = ((xc - bw / 2f) / inW).coerceIn(0f, 1f)
            val top    = ((yc - bh / 2f) / inH).coerceIn(0f, 1f)
            val right  = ((xc + bw / 2f) / inW).coerceIn(0f, 1f)
            val bottom = ((yc + bh / 2f) / inH).coerceIn(0f, 1f)

            if ((bottom - top) * srcH < config.minFaceHeightPx) continue

            val landmarks = if (valPerDet >= 15) {
                (0 until 5).map { k ->
                    (flat[base + 5 + k * 2]     / inW).coerceIn(0f, 1f) to
                    (flat[base + 5 + k * 2 + 1] / inH).coerceIn(0f, 1f)
                }
            } else emptyList()

            candidates.add(
                DetectionResult(
                    score                = score,
                    leftNorm             = left,
                    topNorm              = top,
                    rightNorm            = right,
                    bottomNorm           = bottom,
                    landmarks            = landmarks,
                    detectorId           = detectorId,
                    detectorModelVersion = modelVersionKey
                )
            )
        }

        return nms(candidates, config.nmsIouThreshold)
    }

    override fun close() {
        runtime.close()
        Log.d(TAG, "closed")
    }

    // ── Input preprocessing ───────────────────────────────────────────────────

    /**
     * Builds NCHW [1, 3, H, W] float32 buffer in native byte order.
     * Channel order: R, G, B. Pixel values: [0, 255] unchanged.
     */
    private fun buildInputBuffer(bitmap: Bitmap, w: Int, h: Int): ByteBuffer {
        val scaled = if (bitmap.width == w && bitmap.height == h) bitmap
                     else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val buf = ByteBuffer.allocateDirect(1 * 3 * h * w * 4).order(ByteOrder.nativeOrder())
        for (px in pixels) buf.putFloat((px shr 16 and 0xFF).toFloat())  // R plane
        for (px in pixels) buf.putFloat((px shr 8  and 0xFF).toFloat())  // G plane
        for (px in pixels) buf.putFloat((px        and 0xFF).toFloat())  // B plane
        buf.rewind()
        return buf
    }

    // ── NMS ───────────────────────────────────────────────────────────────────

    private fun nms(detections: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        val sorted     = detections.sortedByDescending { it.score }
        val suppressed = BooleanArray(sorted.size)
        val kept       = mutableListOf<DetectionResult>()
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            for (j in (i + 1) until sorted.size) {
                if (!suppressed[j] && iou(sorted[i], sorted[j]) > iouThreshold) suppressed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: DetectionResult, b: DetectionResult): Float {
        val ix1 = max(a.leftNorm, b.leftNorm);   val iy1 = max(a.topNorm, b.topNorm)
        val ix2 = min(a.rightNorm, b.rightNorm); val iy2 = min(a.bottomNorm, b.bottomNorm)
        val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        val union = a.widthNorm * a.heightNorm + b.widthNorm * b.heightNorm - inter
        return if (union <= 0f) 0f else inter / union
    }
}
