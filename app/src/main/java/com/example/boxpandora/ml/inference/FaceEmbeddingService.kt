package com.example.boxpandora.ml.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.boxpandora.ml.config.AiFeatureFlags
import com.example.boxpandora.ml.detection.MediaType
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.runtime.ModelRuntime
import com.example.boxpandora.ml.runtime.ModelRuntimeFactory
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "FaceEmbeddingService"

/** Expected dimensions of an ArcFace-aligned face crop. */
private const val ALIGNED_FACE_SIZE = 112

/**
 * Runs face embedding inference using the active [ModelCategory.FACE_EMBEDDING] model.
 *
 * **Input contract:** only pre-aligned 112×112 face crops produced by
 * [FaceDetectionService.alignFace]. Passing a full image or an unaligned crop will throw
 * [IllegalArgumentException].
 *
 * **Media-type gating:** GIF and video frames are blocked from the people recognition pipeline
 * by [AiFeatureFlags.isFaceEmbeddingAllowed]. Pass [MediaType.IMAGE] for still images; any
 * other media type throws [FaceEmbeddingBlockedException] unless the matching flag is enabled.
 *
 * Input preprocessing is format-aware:
 *   TFLite (NHWC): uses TFLite Support ImageProcessor with NormalizeOp(127.5, 127.5).
 *   ONNX   (NCHW): manually builds a [1, 3, 112, 112] float32 ByteBuffer with the same
 *                  normalisation range, suitable for ArcFace ONNX models.
 *
 * The returned embedding is L2-normalised so cosine similarity equals the dot product.
 *
 * Lifecycle: create once per worker invocation; call [close] when done. Not thread-safe.
 */
class FaceEmbeddingService(
    context: Context,
    modelManager: ModelManager
) : Closeable {

    private val runtime: ModelRuntime
    private val isOnnx: Boolean
    private val imageProcessor: ImageProcessor   // used for TFLite path only
    private val inputWidth: Int
    private val inputHeight: Int
    val outputDim: Int

    /** Manifest identifier of the active ArcFace model (e.g. "arcface_resnet100_fp16"). */
    val modelId: String

    /** The Room model_version key written into FaceEmbedding rows. */
    val modelVersionKey: String

    init {
        val active = modelManager.getActiveModel(ModelCategory.FACE_EMBEDDING)

        val installed = active
            ?: throw FaceEmbeddingException(
                "No face embedding model is installed. " +
                "Download one via Settings → AI Models before face recognition can run."
            )

        inputWidth      = installed.metadata.inputWidth
        inputHeight     = installed.metadata.inputHeight
        outputDim       = installed.metadata.outputDim
        modelId         = installed.metadata.id
        modelVersionKey = installed.metadata.roomVersionKey
        isOnnx          = installed.metadata.format.equals("onnx", ignoreCase = true)

        runtime = ModelRuntimeFactory.create(installed.metadata, installed.file)

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f))
            .build()

        Log.i(TAG, "Runtime ready [${installed.metadata.format}] — outputDim: $outputDim, model: $modelVersionKey")
    }

    /**
     * Embeds [alignedFaceBitmap] and returns a [FaceEmbedResult] with the L2-normalised
     * embedding and model provenance.
     *
     * @param alignedFaceBitmap Must be a 112×112 ArcFace-aligned crop from
     *                          [FaceDetectionService.alignFace]. Throws [IllegalArgumentException]
     *                          if dimensions differ.
     * @param mediaType         Source media type. GIF and video are blocked unless the
     *                          corresponding [AiFeatureFlags] flag is enabled.
     *                          Throws [FaceEmbeddingBlockedException] if blocked.
     *
     * The bitmap is not recycled by this call.
     */
    fun embed(
        alignedFaceBitmap: Bitmap,
        mediaType: MediaType = MediaType.IMAGE
    ): FaceEmbedResult {
        require(
            alignedFaceBitmap.width == ALIGNED_FACE_SIZE &&
            alignedFaceBitmap.height == ALIGNED_FACE_SIZE
        ) {
            "FaceEmbeddingService requires a ${ALIGNED_FACE_SIZE}×${ALIGNED_FACE_SIZE} aligned crop; " +
            "got ${alignedFaceBitmap.width}×${alignedFaceBitmap.height}. " +
            "Use FaceDetectionService.alignFace() to produce the input."
        }

        if (!AiFeatureFlags.isFaceEmbeddingAllowed(mediaType)) {
            throw FaceEmbeddingBlockedException(
                "Face embedding blocked for media type $mediaType. " +
                "Enable AiFeatureFlags.FACE_EMBEDDING_ON_${mediaType.name} to allow it."
            )
        }

        val inputBuffer = if (isOnnx) buildNchwBuffer(alignedFaceBitmap)
                          else buildNhwcBuffer(alignedFaceBitmap)
        val raw = runtime.run(inputBuffer)[0]
        return FaceEmbedResult(
            embedding    = EmbeddingUtils.l2Normalize(raw),
            modelId      = modelId,
            modelVersion = modelVersionKey
        )
    }

    override fun close() {
        runtime.close()
        Log.d(TAG, "Runtime closed")
    }

    // ── Preprocessing helpers ─────────────────────────────────────────────────

    /**
     * TFLite path — NHWC [1, H, W, 3], normalised to [-1, 1].
     * Delegates to TFLite Support ImageProcessor for resize + normalise.
     */
    private fun buildNhwcBuffer(bitmap: Bitmap): ByteBuffer {
        val tensorImage = TensorImage.fromBitmap(bitmap)
        return imageProcessor.process(tensorImage).buffer
    }

    /**
     * ONNX path — NCHW [1, 3, H, W], normalised to [-1, 1].
     * ArcFace training preprocessing: (pixel − 127.5) / 127.5.
     */
    private fun buildNchwBuffer(bitmap: Bitmap): ByteBuffer {
        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) bitmap
                     else Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (scaled !== bitmap) scaled.recycle()

        // NCHW: channel-first layout [R channel plane, G channel plane, B channel plane]
        val buf = ByteBuffer.allocateDirect(1 * 3 * inputHeight * inputWidth * 4)
            .order(ByteOrder.nativeOrder())

        // R plane
        for (px in pixels) buf.putFloat(((px shr 16 and 0xFF) - 127.5f) / 127.5f)
        // G plane
        for (px in pixels) buf.putFloat(((px shr 8  and 0xFF) - 127.5f) / 127.5f)
        // B plane
        for (px in pixels) buf.putFloat(((px        and 0xFF) - 127.5f) / 127.5f)

        buf.rewind()
        return buf
    }
}

class FaceEmbeddingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Thrown when [FaceEmbeddingService.embed] is called with a media type that is currently blocked. */
class FaceEmbeddingBlockedException(message: String) : RuntimeException(message)
