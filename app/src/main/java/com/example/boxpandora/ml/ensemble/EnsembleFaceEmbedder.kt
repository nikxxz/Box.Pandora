package com.example.boxpandora.ml.ensemble

import android.graphics.Bitmap
import android.util.Log
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.inference.FaceEmbedResult
import com.example.boxpandora.ml.model.InstalledModel
import com.example.boxpandora.ml.runtime.ModelRuntimeFactory
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "EnsembleFaceEmbedder"
private const val ALIGNED_FACE_SIZE = 112

/**
 * Runs face embedding inference for a specific [InstalledModel] without requiring it to be
 * the active model in [ModelManager].
 *
 * Used by [FaceEnsembleOrchestrator] to create one embedder per enabled face recognizer model.
 * Replicates the preprocessing and inference logic from [FaceEmbeddingService]; the input
 * contract is the same: only pre-aligned 112×112 face crops from [FaceDetectionEngine.alignFace].
 *
 * Lifecycle: create once per ensemble run, call [close] in a finally block. Not thread-safe.
 */
class EnsembleFaceEmbedder(installed: InstalledModel) : Closeable {

    private val runtime        = ModelRuntimeFactory.create(installed.metadata, installed.file)
    private val isOnnx         = installed.metadata.format.equals("onnx", ignoreCase = true)
    private val inputWidth     = installed.metadata.inputWidth
    private val inputHeight    = installed.metadata.inputHeight
    val outputDim              = installed.metadata.outputDim
    val modelId                = installed.metadata.id
    val modelVersionKey        = installed.metadata.roomVersionKey

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(127.5f, 127.5f))
        .build()

    init {
        Log.i(TAG, "Ready [${installed.metadata.format}/${installed.metadata.id}] " +
            "outputDim=$outputDim version=$modelVersionKey")
    }

    /**
     * Embeds [alignedFaceBitmap] and returns a [FaceEmbedResult] with an L2-normalised embedding.
     *
     * The bitmap must be a 112×112 ArcFace-aligned crop; throws [IllegalArgumentException] otherwise.
     * The bitmap is not recycled by this call.
     */
    fun embed(alignedFaceBitmap: Bitmap): FaceEmbedResult {
        require(
            alignedFaceBitmap.width == ALIGNED_FACE_SIZE &&
            alignedFaceBitmap.height == ALIGNED_FACE_SIZE
        ) {
            "EnsembleFaceEmbedder requires a ${ALIGNED_FACE_SIZE}×${ALIGNED_FACE_SIZE} aligned crop; " +
            "got ${alignedFaceBitmap.width}×${alignedFaceBitmap.height}"
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
        Log.d(TAG, "Closed [$modelId]")
    }

    // ── Preprocessing helpers (mirror of FaceEmbeddingService) ────────────────

    private fun buildNhwcBuffer(bitmap: Bitmap): ByteBuffer {
        val tensorImage = TensorImage.fromBitmap(bitmap)
        return imageProcessor.process(tensorImage).buffer
    }

    private fun buildNchwBuffer(bitmap: Bitmap): ByteBuffer {
        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) bitmap
                     else Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (scaled !== bitmap) scaled.recycle()

        val buf = ByteBuffer.allocateDirect(1 * 3 * inputHeight * inputWidth * 4)
            .order(ByteOrder.nativeOrder())
        for (px in pixels) buf.putFloat(((px shr 16 and 0xFF) - 127.5f) / 127.5f)
        for (px in pixels) buf.putFloat(((px shr 8  and 0xFF) - 127.5f) / 127.5f)
        for (px in pixels) buf.putFloat(((px        and 0xFF) - 127.5f) / 127.5f)
        buf.rewind()
        return buf
    }
}
