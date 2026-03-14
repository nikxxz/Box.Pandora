package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.FaceScanLog
import com.example.boxpandora.ml.clustering.FaceClusterEngine
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.model.ModelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "FaceClusterWorker"

/**
 * Runs a full face-clustering rebuild using all face embeddings stored in the database.
 *
 * Run order: [FaceIndexWorker] must have run first to populate face_embeddings.
 * Typically enqueued by [AiIndexScheduler.scheduleFaceClusterIfEnabled] after face indexing
 * completes, or triggered by the user via "Rebuild People" in Settings.
 *
 * What this worker does:
 *  1. Reads all face embeddings + detected_face quality scores for the active embedder version.
 *  2. Reads all user corrections (hard constraints).
 *  3. Reads all existing cluster metadata (names, confirmed status, hidden flag).
 *  4. Runs [FaceClusterEngine] — conservative centroid-based clustering.
 *  5. Writes updated FaceCluster rows (upsert) and sets detected_faces.cluster_id.
 *  6. Prunes orphan clusters (n=0, never confirmed by user).
 *
 * Idempotency: safe to re-run. Each run is a full rebuild; results are deterministic given
 * the same embeddings and corrections.
 *
 * Cancellation: checked between the read phase and the write phase. The write phase is not
 * interrupted mid-way to avoid partial state.
 */
class FaceClusterWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp

        val settings = app.aiSettingsRepository.settings.first()
        if (!settings.faceProcessingEnabled) {
            Log.i(TAG, "Face processing disabled — worker exiting early")
            return@withContext Result.success()
        }

        val faceDao        = app.database.faceDao()
        val clusterDao     = app.database.faceClusterDao()
        val modelManager   = app.modelManager

        // Determine active embedder version — we need it to load the right embeddings
        val activeEmbedder = modelManager.getActiveModel(ModelCategory.FACE_EMBEDDING)
        if (activeEmbedder == null) {
            Log.w(TAG, "No active face embedding model — aborting clustering")
            return@withContext Result.failure(workDataOf("error" to "No face embedding model installed"))
        }
        val embedderVersion = activeEmbedder.metadata.roomVersionKey

        // ── 1. Load faces with embeddings ─────────────────────────────────────
        val rows = faceDao.getAllEmbeddingsForClustering(embedderVersion)
        if (rows.isEmpty()) {
            Log.i(TAG, "No face embeddings found for embedder $embedderVersion — nothing to cluster")
            return@withContext Result.success(workDataOf("clustersBuilt" to 0))
        }
        Log.i(TAG, "Clustering ${rows.size} faces (embedder: $embedderVersion)")

        val faceInputs = rows.map { row ->
            FaceClusterEngine.FaceInput(
                faceId       = row.face_id,
                embedding    = EmbeddingUtils.bytesToFloatArray(row.embedding),
                qualityScore = row.quality_score
            )
        }

        // ── 2. Load corrections and existing clusters ─────────────────────────
        val corrections      = clusterDao.getAllCorrections()
        val existingClusters = clusterDao.getAllClusters()

        if (!isActive) {
            Log.d(TAG, "Cancelled before write phase")
            return@withContext Result.success()
        }

        // ── 3. Run clustering engine ──────────────────────────────────────────
        val engine = FaceClusterEngine()
        val result = engine.run(
            faces            = faceInputs,
            existingClusters = existingClusters,
            corrections      = corrections
        )

        // ── 4. Write results ──────────────────────────────────────────────────
        // Clear all cluster_id assignments, then bulk-write new ones
        clusterDao.clearAllClusterAssignments()

        // Upsert all cluster entities (preserves name/confirmed/hidden from existing rows)
        clusterDao.insertClusters(result.clusters)

        // Write face→cluster assignments in batches to avoid SQLite bind-variable limits
        val batchSize = 500
        result.assignments.entries.chunked(batchSize).forEach { batch ->
            // Group by cluster for bulk update
            batch.groupBy { it.value }.forEach { (clusterId, entries) ->
                val faceIds = entries.map { it.key }
                clusterDao.assignFacesToCluster(faceIds, clusterId)
            }
        }

        // Remove clusters that ended up with no faces and were never confirmed
        clusterDao.pruneOrphanClusters()

        val clustersBuilt = result.clusters.count { it.n > 0 }
        Log.i(TAG, "Clustering complete — ${result.assignments.size} faces assigned, $clustersBuilt active clusters")

        Result.success(workDataOf(
            "facesAssigned" to result.assignments.size,
            "clustersBuilt" to clustersBuilt
        ))
    }
}
