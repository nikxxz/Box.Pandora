package com.example.boxpandora.ml.detection

import android.graphics.Bitmap
import android.util.Log
import com.example.boxpandora.ml.model.ModelMetadata
import com.example.boxpandora.ml.runtime.ModelRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val TAG = "ScrfdDetectionEngine"

/**
 * Face detector engine for the SCRFD bnkps ONNX model.
 *
 * Input format:  NCHW [1, 3, H, W], float32, RGB, normalised (pixel − 127.5) / 128.
 *
 * Output format: 9 tensors in model-definition order (3 strides × 3 tensor types):
 *   [0–2] score_8,  score_16,  score_32   — shape [1, A_s, 1]  pre-sigmoid logits
 *   [3–5] bbox_8,   bbox_16,   bbox_32    — shape [1, A_s, 4]  distance predictions (ltrb)
 *   [6–8] kps_8,    kps_16,    kps_32    — shape [1, A_s, 10] keypoint predictions
 *
 * Anchor layout: 2 anchors per spatial location, row-major (y first), per-stride.
 * Strides: [8, 16, 32]. Anchor centres: ((col + 0.5) * stride, (row + 0.5) * stride).
 *
 * Decoding:
 *   x1 = cx − dl * stride  (dl = bbox pred 0)
 *   y1 = cy − dt * stride  (dt = bbox pred 1)
 *   x2 = cx + dr * stride  (dr = bbox pred 2)
 *   y2 = cy + db * stride  (db = bbox pred 3)
 *   lm = (cx + kps_pred[k*2] * stride, cy + kps_pred[k*2+1] * stride)
 *   score = sigmoid(raw_logit)
 *
 * This class owns the [runtime] and closes it in [close].
 */
class ScrfdDetectionEngine(
    private val runtime: ModelRuntime,
    private val meta: ModelMetadata,
    override val config: DetectorConfig = DetectorConfig.DEFAULT
) : FaceDetectionEngine {

    override val detectorId: String      = meta.id
    override val modelVersionKey: String = meta.roomVersionKey

    private val strides            = intArrayOf(8, 16, 32)
    private val numAnchorsPerLoc   = 2

    override fun detect(bitmap: Bitmap): List<DetectionResult> {
        val inputBuffer = buildInputBuffer(bitmap, meta.inputWidth, meta.inputHeight)
        val outputs     = runtime.run(inputBuffer)

        if (outputs.size < 9) {
            Log.w(TAG, "Expected 9 output tensors, got ${outputs.size} — skipping")
            return emptyList()
        }

        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val inW  = meta.inputWidth.toFloat()
        val inH  = meta.inputHeight.toFloat()

        // ── Generate anchors ─────────────────────────────────────────────────
        val totalAnchors = strides.sumOf { s ->
            (meta.inputHeight / s) * (meta.inputWidth / s) * numAnchorsPerLoc
        }
        val anchorCx     = FloatArray(totalAnchors)
        val anchorCy     = FloatArray(totalAnchors)
        val anchorStride = IntArray(totalAnchors)
        var idx = 0
        for (stride in strides) {
            val fh = meta.inputHeight / stride
            val fw = meta.inputWidth  / stride
            for (y in 0 until fh) {
                for (x in 0 until fw) {
                    repeat(numAnchorsPerLoc) {
                        anchorCx[idx]     = (x + 0.5f) * stride
                        anchorCy[idx]     = (y + 0.5f) * stride
                        anchorStride[idx] = stride
                        idx++
                    }
                }
            }
        }

        // ── Concatenate per-stride scores, bboxes, keypoints ─────────────────
        // outputs[0..2]: scores (3 strides), outputs[3..5]: bboxes, outputs[6..8]: kps
        val scores = concatOutputs(outputs, 0, 3)   // [totalAnchors]      — 1 value each
        val bboxes = concatOutputs(outputs, 3, 6)   // [totalAnchors * 4]  — 4 values each
        val kps    = concatOutputs(outputs, 6, 9)   // [totalAnchors * 10] — 10 values each
        val hasKps = kps.size == totalAnchors * 10

        // ── Decode ────────────────────────────────────────────────────────────
        val candidates = mutableListOf<DetectionResult>()
        for (i in 0 until totalAnchors) {
            // Guard against output size mismatch; sigmoid(-∞) = 0 which fails threshold
            val score = sigmoid(scores.getOrElse(i) { Float.NEGATIVE_INFINITY })
            if (score < config.confidenceThreshold) continue
            if (i * 4 + 3 >= bboxes.size) continue

            val s  = anchorStride[i].toFloat()
            val cx = anchorCx[i]; val cy = anchorCy[i]

            val x1 = (cx - bboxes[i * 4 + 0] * s) / inW
            val y1 = (cy - bboxes[i * 4 + 1] * s) / inH
            val x2 = (cx + bboxes[i * 4 + 2] * s) / inW
            val y2 = (cy + bboxes[i * 4 + 3] * s) / inH

            val left   = x1.coerceIn(0f, 1f); val top    = y1.coerceIn(0f, 1f)
            val right  = x2.coerceIn(0f, 1f); val bottom = y2.coerceIn(0f, 1f)

            if ((bottom - top) * srcH < config.minFaceHeightPx) continue

            val landmarks: List<Pair<Float, Float>> = if (hasKps) {
                (0 until 5).map { k ->
                    val lx = ((cx + kps[i * 10 + k * 2 + 0] * s) / inW).coerceIn(0f, 1f)
                    val ly = ((cy + kps[i * 10 + k * 2 + 1] * s) / inH).coerceIn(0f, 1f)
                    lx to ly
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
     * Builds NCHW [1, 3, H, W] float32 buffer.
     * SCRFD normalisation: (pixel − 127.5) / 128.
     */
    private fun buildInputBuffer(bitmap: Bitmap, w: Int, h: Int): ByteBuffer {
        val scaled = if (bitmap.width == w && bitmap.height == h) bitmap
                     else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val buf = ByteBuffer.allocateDirect(1 * 3 * h * w * 4).order(ByteOrder.nativeOrder())
        for (px in pixels) buf.putFloat(((px shr 16 and 0xFF) - 127.5f) / 128f)  // R
        for (px in pixels) buf.putFloat(((px shr 8  and 0xFF) - 127.5f) / 128f)  // G
        for (px in pixels) buf.putFloat(((px        and 0xFF) - 127.5f) / 128f)  // B
        buf.rewind()
        return buf
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Concatenates flattened output tensors at indices [fromIdx, toIdx). */
    private fun concatOutputs(outputs: List<FloatArray>, fromIdx: Int, toIdx: Int): FloatArray {
        val totalSize = (fromIdx until toIdx).sumOf { outputs[it].size }
        val result = FloatArray(totalSize)
        var offset = 0
        for (i in fromIdx until toIdx) { outputs[i].copyInto(result, offset); offset += outputs[i].size }
        return result
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

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
