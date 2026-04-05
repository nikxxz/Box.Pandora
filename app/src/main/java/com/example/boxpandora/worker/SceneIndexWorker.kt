package com.example.boxpandora.worker

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.ImageEmbedding
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.inference.SceneEmbeddingException
import com.example.boxpandora.ml.inference.SceneEmbeddingService
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.sampling.FrameSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "SceneIndexWorker"
private const val BATCH_SIZE = 8

/**
 * Indexes all un-embedded media (plain images, GIFs, videos) with the active scene model.
 *
 * Processing rules by media type:
 *   Images (non-GIF)  — load bitmap directly, run scene embedding.
 *   GIFs              — sample representative frame(s) via [FrameSampler], aggregate to mean,
 *                       store one embedding per GIF.
 *   Videos            — sample frames via [FrameSampler], aggregate to mean,
 *                       store one embedding per video.
 *
 * Face detection is NOT run here — that is FaceIndexWorker's responsibility.
 *
 * Design constraints:
 *  - Idempotent: assets already indexed with the current model version are skipped.
 *  - Cancellation-aware: checks [isActive] between batches.
 *  - One [SceneEmbeddingService] instance shared across all passes, closed in finally.
 *  - Individual asset failures are logged and skipped; the worker only fails if the model
 *    itself cannot load.
 */
class SceneIndexWorker(
    context: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp
        val embeddingDao = app.database.imageEmbeddingDao()
        val statsStore = app.indexingStatsStore
        val category = ModelCategory.SCENE_EMBEDDING

        // ── Crash loop guard ──────────────────────────────────────────────────
        if (app.modelManager.hasTooManyFailures(category)) {
            Log.e(TAG, "Too many model failures — scene indexing suspended until model is reinstalled")
            return@withContext Result.failure(workDataOf("error" to "too_many_failures"))
        }

        // ── Integrity check + auto-fallback ───────────────────────────────────
        if (!app.modelManager.verifyActiveModel(category)) {
            Log.w(TAG, "Active scene model failed integrity check — attempting fallback")
            app.modelManager.getActiveModelWithFallback(category)
        }

        val service = try {
            SceneEmbeddingService(applicationContext, app.modelManager)
        } catch (e: SceneEmbeddingException) {
            Log.e(TAG, "Model unavailable — scene indexing aborted: ${e.message}")
            app.modelManager.recordModelFailure(category)
            return@withContext Result.failure(workDataOf("error" to (e.message ?: "model_unavailable")))
        }

        app.modelManager.clearModelFailures(category)

        // Optional album filter — set when launched from "Scan by Folder"
        val albumId: Long? = inputData.getLong(KEY_ALBUM_ID, -1L).takeIf { it != -1L }

        service.use {
            val modelVersion = service.modelVersionKey

            val imageCount = if (albumId != null) embeddingDao.countUnindexedByAlbum(albumId, modelVersion)
                             else embeddingDao.countUnindexed(modelVersion)
            val gifCount   = if (albumId != null) embeddingDao.countUnindexedGifsByAlbum(albumId, modelVersion)
                             else embeddingDao.countUnindexedGifs(modelVersion)
            val videoCount = if (albumId != null) embeddingDao.countUnindexedVideosByAlbum(albumId, modelVersion)
                             else embeddingDao.countUnindexedVideos(modelVersion)
            val total      = imageCount + gifCount + videoCount

            val scope = if (albumId != null) "album=$albumId" else "full library"
            Log.i(TAG, "Scene index run [$scope] — images:$imageCount gifs:$gifCount videos:$videoCount (model: $modelVersion)")

            if (total == 0) {
                Log.i(TAG, "Nothing to index — all assets up to date")
                statsStore.recordRun(
                    pipeline = IndexingStatsStore.PIPELINE_SCENE,
                    indexedCount = 0, skippedCount = 0, inferenceFailures = 0,
                    avgProcessingTimeMs = 0L, wasCancelled = false
                )
                return@withContext Result.success()
            }

            var indexed = 0
            var failures = 0
            var totalProcessingMs = 0L

            // ── Pass 1: plain images ──────────────────────────────────────────
            var offset = 0
            while (isActive) {
                val uris = if (albumId != null)
                    embeddingDao.getUnindexedImageUrisByAlbum(albumId, modelVersion, BATCH_SIZE, offset)
                else
                    embeddingDao.getUnindexedImageUris(modelVersion, BATCH_SIZE, offset)
                if (uris.isEmpty()) break
                val batch = mutableListOf<ImageEmbedding>()
                for (uri in uris) {
                    if (!isActive) break
                    val t0 = System.currentTimeMillis()
                    runCatching { embedImage(uri, service) }.onSuccess { e ->
                        batch.add(e); indexed++; totalProcessingMs += System.currentTimeMillis() - t0
                    }.onFailure { e ->
                        Log.w(TAG, "Image skip $uri: ${e.message}"); failures++
                    }
                }
                if (batch.isNotEmpty()) embeddingDao.insertAll(batch)
                setProgress(workDataOf("indexed" to indexed, "total" to total))
                offset += BATCH_SIZE
            }

            // ── Pass 2: GIFs ──────────────────────────────────────────────────
            offset = 0
            while (isActive) {
                val uris = if (albumId != null)
                    embeddingDao.getUnindexedGifUrisByAlbum(albumId, modelVersion, BATCH_SIZE, offset)
                else
                    embeddingDao.getUnindexedGifUris(modelVersion, BATCH_SIZE, offset)
                if (uris.isEmpty()) break
                val batch = mutableListOf<ImageEmbedding>()
                for (uri in uris) {
                    if (!isActive) break
                    val t0 = System.currentTimeMillis()
                    runCatching { embedGif(uri, service) }.onSuccess { e ->
                        if (e != null) { batch.add(e); indexed++; totalProcessingMs += System.currentTimeMillis() - t0 }
                        else { Log.w(TAG, "GIF produced no frames: $uri"); failures++ }
                    }.onFailure { e ->
                        Log.w(TAG, "GIF skip $uri: ${e.message}"); failures++
                    }
                }
                if (batch.isNotEmpty()) embeddingDao.insertAll(batch)
                setProgress(workDataOf("indexed" to indexed, "total" to total))
                offset += BATCH_SIZE
            }

            // ── Pass 3: videos ────────────────────────────────────────────────
            offset = 0
            while (isActive) {
                val uris = if (albumId != null)
                    embeddingDao.getUnindexedVideoUrisByAlbum(albumId, modelVersion, BATCH_SIZE, offset)
                else
                    embeddingDao.getUnindexedVideoUris(modelVersion, BATCH_SIZE, offset)
                if (uris.isEmpty()) break
                val batch = mutableListOf<ImageEmbedding>()
                for (uri in uris) {
                    if (!isActive) break
                    val t0 = System.currentTimeMillis()
                    runCatching { embedVideo(uri, service) }.onSuccess { e ->
                        if (e != null) { batch.add(e); indexed++; totalProcessingMs += System.currentTimeMillis() - t0 }
                        else { Log.w(TAG, "Video produced no frames: $uri"); failures++ }
                    }.onFailure { e ->
                        Log.w(TAG, "Video skip $uri: ${e.message}"); failures++
                    }
                }
                if (batch.isNotEmpty()) embeddingDao.insertAll(batch)
                setProgress(workDataOf("indexed" to indexed, "total" to total))
                offset += BATCH_SIZE
            }

            val cancelled = !isActive
            val avgMs = if (indexed > 0) totalProcessingMs / indexed else 0L
            val skipped = (total - indexed - failures).coerceAtLeast(0)
            statsStore.recordRun(
                pipeline            = IndexingStatsStore.PIPELINE_SCENE,
                indexedCount        = indexed,
                skippedCount        = skipped,
                inferenceFailures   = failures,
                avgProcessingTimeMs = avgMs,
                wasCancelled        = cancelled
            )

            Log.i(TAG, "Scene index complete — $indexed indexed, $failures failures, cancelled=$cancelled")
            Result.success(workDataOf("indexed" to indexed, "failures" to failures))
        }
    }

    // ── Per-media-type embedding helpers ─────────────────────────────────────

    private fun embedImage(uriString: String, service: SceneEmbeddingService): ImageEmbedding {
        val uri = Uri.parse(uriString)
        val bitmap = applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalStateException("Could not open bitmap for $uriString")

        val result = try {
            service.embed(bitmap, SceneEmbeddingService.MEDIA_TYPE_IMAGE)
        } finally {
            bitmap.recycle()
        }
        return result.toEntity(uriString)
    }

    /**
     * Samples frame(s) from a GIF, aggregates embeddings to a normalised mean,
     * and returns one [ImageEmbedding] row. Returns null if no frames could be sampled.
     */
    private fun embedGif(uriString: String, service: SceneEmbeddingService): ImageEmbedding? {
        val frames = FrameSampler.sampleGifFrames(applicationContext, Uri.parse(uriString))
        if (frames.isEmpty()) return null

        val vectors = frames.map { frame ->
            try {
                service.embed(frame.bitmap, SceneEmbeddingService.MEDIA_TYPE_GIF).embedding
            } finally {
                frame.bitmap.recycle()
            }
        }

        val scanMethod = if (frames.size == 1) frames.first().scanMethod
                         else "sparse_${frames.size}_mean"
        val embedding  = EmbeddingUtils.normalizedMean(vectors) ?: return null
        return ImageEmbedding(
            assetId      = uriString,
            modelVersion = service.modelVersionKey,
            dim          = embedding.size,
            embedding    = EmbeddingUtils.floatArrayToBytes(embedding),
            mediaType    = SceneEmbeddingService.MEDIA_TYPE_GIF,
            scanMethod   = scanMethod
        )
    }

    /**
     * Samples frames from a video at uniform intervals, aggregates embeddings to a
     * normalised mean, and returns one [ImageEmbedding] row. Returns null if no frames
     * could be sampled.
     */
    private fun embedVideo(uriString: String, service: SceneEmbeddingService): ImageEmbedding? {
        val frames = FrameSampler.sampleVideoFrames(applicationContext, Uri.parse(uriString))
        if (frames.isEmpty()) return null

        val vectors = frames.map { frame ->
            try {
                service.embed(frame.bitmap, SceneEmbeddingService.MEDIA_TYPE_VIDEO).embedding
            } finally {
                frame.bitmap.recycle()
            }
        }

        val scanMethod = if (frames.size == 1) frames.first().scanMethod
                         else "uniform_${frames.size}_mean"
        val embedding  = EmbeddingUtils.normalizedMean(vectors) ?: return null
        return ImageEmbedding(
            assetId      = uriString,
            modelVersion = service.modelVersionKey,
            dim          = embedding.size,
            embedding    = EmbeddingUtils.floatArrayToBytes(embedding),
            mediaType    = SceneEmbeddingService.MEDIA_TYPE_VIDEO,
            scanMethod   = scanMethod
        )
    }
}

// ── Extension ─────────────────────────────────────────────────────────────────

private fun com.example.boxpandora.ml.inference.SceneEmbedResult.toEntity(assetId: String) =
    ImageEmbedding(
        assetId      = assetId,
        modelVersion = modelVersion,
        dim          = embedding.size,
        embedding    = EmbeddingUtils.floatArrayToBytes(embedding),
        mediaType    = mediaType,
        scanMethod   = scanMethod
    )
