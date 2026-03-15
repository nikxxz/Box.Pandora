package com.example.boxpandora.ml.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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

private const val TAG = "SceneEmbeddingService"

/**
 * Runs scene embedding inference using the active [ModelCategory.SCENE_EMBEDDING] model.
 *
 * Runtime is selected dynamically from the active model's format via [ModelRuntimeFactory].
 * Currently only TFLite scene models are declared; the service will also work if an ONNX
 * scene model is added to the manifest in the future.
 *
 * MobileNet V3 normalisation: pixel values scaled to [-1, 1]
 *   mean = 127.5, std = 127.5  →  (pixel / 255 − 0.5) × 2
 *
 * Accepts any pre-decoded [Bitmap]: a still image, a sampled GIF frame, or a sampled video
 * frame. The caller is responsible for frame selection; this service only handles inference.
 *
 * Preprocessing logic lives here exclusively — workers must not duplicate it.
 *
 * Lifecycle: create one instance per worker invocation; call [close] in a finally block.
 * Not thread-safe.
 */
class SceneEmbeddingService(
    context: Context,
    modelManager: ModelManager
) : Closeable {

    private val runtime: ModelRuntime
    private val imageProcessor: ImageProcessor
    private val inputWidth: Int
    private val inputHeight: Int
    val outputDim: Int

    /** Manifest id of the active model (e.g. "mobilenet_v3_scene"). */
    val modelId: String

    /** Room-safe version key written into [ImageEmbedding.modelVersion] rows. */
    val modelVersionKey: String

    init {
        val active = modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)

        val installed = active
            ?: throw SceneEmbeddingException(
                "No scene embedding model is installed. " +
                "Download one via Settings → AI Models before indexing can begin."
            )

        inputWidth      = installed.metadata.inputWidth
        inputHeight     = installed.metadata.inputHeight
        outputDim       = installed.metadata.outputDim
        modelId         = installed.metadata.id
        modelVersionKey = installed.metadata.roomVersionKey

        runtime = ModelRuntimeFactory.create(installed.metadata, installed.file)

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f))
            .build()

        Log.i(TAG, "Runtime ready [${installed.metadata.format}] — input: ${inputWidth}x${inputHeight}, outputDim: $outputDim")
    }

    /**
     * Runs inference on [bitmap] and returns a [SceneEmbedResult] carrying the L2-normalised
     * embedding together with model provenance.
     *
     * @param bitmap      Any pre-decoded frame: still image, GIF sample, or video keyframe.
     *                    Not recycled by this call.
     * @param mediaType   "image", "gif", or "video" — stored verbatim in the DB row.
     * @param scanMethod  Optional frame-sampling descriptor (e.g. "uniform_5", "keyframe").
     *                    Pass null for plain images.
     */
    fun embed(
        bitmap: Bitmap,
        mediaType: String = MEDIA_TYPE_IMAGE,
        scanMethod: String? = null
    ): SceneEmbedResult {
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val processed   = imageProcessor.process(tensorImage)
        val outputs     = runtime.run(processed.buffer)
        val embedding   = EmbeddingUtils.l2Normalize(outputs[0])
        return SceneEmbedResult(
            embedding    = embedding,
            modelId      = modelId,
            modelVersion = modelVersionKey,
            mediaType    = mediaType,
            scanMethod   = scanMethod
        )
    }

    override fun close() {
        runtime.close()
        Log.d(TAG, "Runtime closed")
    }

    companion object {
        const val MEDIA_TYPE_IMAGE = "image"
        const val MEDIA_TYPE_GIF   = "gif"
        const val MEDIA_TYPE_VIDEO = "video"
    }
}

class SceneEmbeddingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
