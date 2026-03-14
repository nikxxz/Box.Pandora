package com.example.boxpandora.worker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.DetectedFace
import com.example.boxpandora.data.local.entity.FaceEmbedding
import com.example.boxpandora.ml.detection.MediaType
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.inference.FaceDetectionService
import com.example.boxpandora.ml.inference.FaceDetectorState
import com.example.boxpandora.ml.inference.FaceEmbeddingException
import com.example.boxpandora.ml.inference.FaceEmbeddingService
import com.example.boxpandora.ml.model.ModelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "FaceIndexWorker"
private const val BATCH_SIZE = 4  // Smaller than SceneIndexWorker; detection is heavier per image

/**
 * Scans image-type media that has not yet been processed by the active face detector,
 * runs detection, stores [DetectedFace] rows, then embeds each detected face and stores
 * [FaceEmbedding] rows.
 *
 * Idempotency: assets that already have at least one [DetectedFace] row for the current
 * detector version are skipped.
 *
 * Known Phase 4 limitation: images where zero faces are detected produce no rows in
 * detected_faces, so they are re-processed on every worker run. A face_scan_log table
 * (planned for Phase 5) will track per-asset scan state independently of face count.
 *
 * Design constraints (same as SceneIndexWorker):
 *  - One [FaceDetectionService] and one [FaceEmbeddingService] instance per worker run, closed in finally.
 *  - Failures on individual images or faces are logged and skipped; the worker only returns
 *    FAILURE if the model itself fails to load.
 *  - Cancellation-aware between batches.
 *  - Battery-not-low constraint applied by [AiIndexScheduler].
 */
class FaceIndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp
        val statsStore = app.indexingStatsStore

        val settings = app.aiSettingsRepository.settings.first()
        if (!settings.faceProcessingEnabled) {
            Log.i(TAG, "Face processing disabled — worker exiting early")
            return@withContext Result.success()
        }

        val faceDao = app.database.faceDao()

        // ── Face detection model availability guard ───────────────────────────
        // FaceIndexWorker is skeleton-only until a face detection model is installed.
        // Return success (not failure) so the scheduler does not treat this as an error
        // and does not increment the crash-loop counter.
        if (app.modelManager.getActiveModel(ModelCategory.FACE_DETECTION) == null) {
            Log.i(TAG, "No face detection model installed — face indexing skipped until detector is available")
            return@withContext Result.success(workDataOf("skipped" to "no_detector"))
        }

        // ── Crash loop guard ──────────────────────────────────────────────────
        if (app.modelManager.hasTooManyFailures(ModelCategory.FACE_DETECTION) ||
            app.modelManager.hasTooManyFailures(ModelCategory.FACE_EMBEDDING)) {
            Log.e(TAG, "Too many face model failures — face indexing suspended until models are reinstalled")
            return@withContext Result.failure(workDataOf("error" to "too_many_failures"))
        }

        // ── Integrity check + auto-fallback ───────────────────────────────────
        if (!app.modelManager.verifyActiveModel(ModelCategory.FACE_DETECTION)) {
            Log.w(TAG, "Active face detection model failed integrity check — attempting fallback")
            app.modelManager.getActiveModelWithFallback(ModelCategory.FACE_DETECTION)
        }
        if (!app.modelManager.verifyActiveModel(ModelCategory.FACE_EMBEDDING)) {
            Log.w(TAG, "Active face embedding model failed integrity check — attempting fallback")
            app.modelManager.getActiveModelWithFallback(ModelCategory.FACE_EMBEDDING)
        }

        // Load detector — constructor never throws; check state instead
        val detector = FaceDetectionService(applicationContext, app.modelManager)
        when (val st = detector.state) {
            is FaceDetectorState.Ready -> Unit   // proceed
            FaceDetectorState.NoModelInstalled -> {
                detector.close()
                Log.i(TAG, "No face detection model installed — skipping")
                return@withContext Result.success(workDataOf("skipped" to "no_detector"))
            }
            is FaceDetectorState.UnsupportedFormat -> {
                detector.close()
                Log.w(TAG, "Face detection model format '${st.format}' not supported on this build — skipping")
                return@withContext Result.success(workDataOf("skipped" to "unsupported_format"))
            }
            is FaceDetectorState.LoadFailed -> {
                detector.close()
                Log.e(TAG, "Face detector load failed — aborting: ${st.reason}")
                app.modelManager.recordModelFailure(ModelCategory.FACE_DETECTION)
                return@withContext Result.failure(workDataOf("error" to st.reason))
            }
        }

        // Load embedder — failure here is also fatal for this run
        val embedder = try {
            FaceEmbeddingService(applicationContext, app.modelManager)
        } catch (e: FaceEmbeddingException) {
            detector.close()
            Log.e(TAG, "Face embedder unavailable — aborting: ${e.message}")
            app.modelManager.recordModelFailure(ModelCategory.FACE_EMBEDDING)
            return@withContext Result.failure(workDataOf("error" to e.message))
        }

        // Models loaded successfully — clear failure counters
        app.modelManager.clearModelFailures(ModelCategory.FACE_DETECTION)
        app.modelManager.clearModelFailures(ModelCategory.FACE_EMBEDDING)

        try {
            val detectorVersion = detector.modelVersionKey!!
            val embedderVersion = embedder.modelVersionKey

            val total = faceDao.countUnprocessed(detectorVersion)
            Log.i(TAG, "Face index run — $total images to process " +
                "(detector: $detectorVersion, embedder: $embedderVersion)")

            if (total == 0) {
                Log.i(TAG, "Nothing to process — all images up to date")
                statsStore.recordRun(
                    pipeline = IndexingStatsStore.PIPELINE_FACE,
                    indexedCount = 0, skippedCount = 0, inferenceFailures = 0,
                    avgProcessingTimeMs = 0L, wasCancelled = false
                )
                return@withContext Result.success()
            }

            var offset = 0
            var imagesProcessed = 0
            var facesDetected = 0
            var embeddingsWritten = 0
            var failures = 0
            var totalProcessingMs = 0L

            while (isActive) {
                val uris = faceDao.getUnprocessedImageUris(detectorVersion, BATCH_SIZE, offset)
                if (uris.isEmpty()) break

                for (uri in uris) {
                    if (!isActive) break
                    val itemStartMs = System.currentTimeMillis()
                    runCatching {
                        processImage(uri, detector, embedder, detectorVersion, embedderVersion)
                    }.onSuccess { (faces, embeddings) ->
                        imagesProcessed++
                        facesDetected += faces
                        embeddingsWritten += embeddings
                        totalProcessingMs += System.currentTimeMillis() - itemStartMs
                    }.onFailure { e ->
                        Log.w(TAG, "Skipping $uri: ${e.message}")
                        failures++
                    }
                }

                setProgress(workDataOf(
                    "imagesProcessed" to imagesProcessed,
                    "total" to total,
                    "facesDetected" to facesDetected
                ))
                Log.d(TAG, "Batch done — $imagesProcessed/$total images, $facesDetected faces detected")

                offset += BATCH_SIZE
            }

            val cancelled = !isActive
            val avgMs = if (imagesProcessed > 0) totalProcessingMs / imagesProcessed else 0L
            statsStore.recordRun(
                pipeline            = IndexingStatsStore.PIPELINE_FACE,
                indexedCount        = imagesProcessed,
                skippedCount        = 0,
                inferenceFailures   = failures,
                avgProcessingTimeMs = avgMs,
                wasCancelled        = cancelled
            )

            Log.i(TAG, "Face index complete — $imagesProcessed images, $facesDetected faces, $embeddingsWritten embeddings, $failures failures")
            Result.success(workDataOf(
                "imagesProcessed" to imagesProcessed,
                "facesDetected" to facesDetected,
                "embeddingsWritten" to embeddingsWritten,
                "failures" to failures
            ))
        } finally {
            embedder.close()
            detector.close()
        }
    }

    /**
     * Processes a single image URI: detect faces, store DetectedFace rows, embed each face,
     * store FaceEmbedding rows.
     *
     * @return Pair(faces detected, embeddings written)
     */
    private suspend fun processImage(
        uriString: String,
        detector: FaceDetectionService,
        embedder: FaceEmbeddingService,
        detectorVersion: String,
        embedderVersion: String
    ): Pair<Int, Int> {
        val uri = Uri.parse(uriString)
        val bitmap = applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalStateException("Could not open bitmap for $uriString")

        val faceDao = (applicationContext as PandoraApp).database.faceDao()

        return try {
            val detections = detector.detect(bitmap, MediaType.IMAGE)

            val faceEntities = mutableListOf<DetectedFace>()
            val embeddingEntities = mutableListOf<FaceEmbedding>()

            for ((faceIndex, detection) in detections.withIndex()) {
                val faceId = "${uriString}_${faceIndex}"

                val face = DetectedFace(
                    faceId = faceId,
                    assetId = uriString,
                    faceIndex = faceIndex,
                    detectorModelVersion = detectorVersion,
                    leftNorm = detection.leftNorm.toDouble(),
                    topNorm = detection.topNorm.toDouble(),
                    rightNorm = detection.rightNorm.toDouble(),
                    bottomNorm = detection.bottomNorm.toDouble(),
                    widthPx = (detection.widthNorm * bitmap.width).toInt(),
                    heightPx = (detection.heightNorm * bitmap.height).toInt(),
                    // Pose angles not estimated in Phase 4; landmark-derived pose is Phase 5
                    yaw = 0.0, pitch = 0.0, roll = 0.0,
                    qualityScore = detection.score.toDouble()
                )
                faceEntities.add(face)

                // Embed the aligned face crop
                runCatching {
                    val crop = detector.alignFace(bitmap, detection)
                    val result = try {
                        embedder.embed(crop, MediaType.IMAGE)
                    } finally {
                        crop.recycle()
                    }
                    embeddingEntities.add(
                        FaceEmbedding(
                            faceId       = faceId,
                            modelId      = result.modelId,
                            modelVersion = result.modelVersion,
                            dim          = result.embedding.size,
                            embedding    = EmbeddingUtils.floatArrayToBytes(result.embedding)
                        )
                    )
                }.onFailure { e ->
                    Log.w(TAG, "Embedding failed for face $faceId: ${e.message}")
                }
            }

            if (faceEntities.isNotEmpty()) {
                faceDao.insertFaces(faceEntities)
            }
            if (embeddingEntities.isNotEmpty()) {
                faceDao.insertEmbeddings(embeddingEntities)
            }

            Pair(faceEntities.size, embeddingEntities.size)
        } finally {
            bitmap.recycle()
        }
    }
}
