package com.example.boxpandora.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.PersonSuggestion
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.model.ModelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "PersonSuggestionWorker"

/**
 * Minimum gap between the top-1 and top-2 cluster similarity before the second-best margin
 * rule fires. Identity matching needs a larger margin than scene tagging (0.08) because
 * misidentifying a person in a photo is a more noticeable error.
 *
 * When the gap between the best and second-best cluster similarity is smaller than this value,
 * the face embedding lies too close to two cluster centroids — the match is ambiguous and the
 * top suggestion is penalised proportionally:
 *   `penaltyFactor = gap / SECOND_BEST_MARGIN`  (0..1 linear decay)
 *
 * If the penalised similarity falls below [PersonSuggestionWorker.SUGGESTION_THRESHOLD], the
 * suggestion is suppressed entirely rather than written with a low-confidence score.
 */
private const val SECOND_BEST_MARGIN = 0.10f

/**
 * Matches all embedded faces against confirmed clusters and writes [PersonSuggestion] rows for
 * the user to review.
 *
 * **Confirmed clusters include:**
 *  - Clusters named/confirmed by the user via the People UI.
 *  - Clusters built from manual person tags by [PersonProfileWorker] (these are written with
 *    [FaceCluster.confirmedByUser] = true and therefore participate here automatically).
 *
 * **Second-best margin rule:**
 *  If the best and second-best cluster similarities are within [SECOND_BEST_MARGIN] of each
 *  other, the top suggestion is ambiguous (the face could plausibly belong to two people).
 *  The top candidate's similarity is penalised proportionally and suppressed if it falls
 *  below [SUGGESTION_THRESHOLD]. This prevents low-confidence suggestions from flooding the
 *  review queue.
 *
 * **Run order:** [PersonProfileWorker] and [FaceClusterWorker] should have run first so that
 * confirmed clusters are up to date. Typically chained via
 * [AiIndexScheduler.schedulePersonSuggestionsIfEnabled].
 *
 * **Idempotency:** The REPLACE conflict strategy on the [PersonSuggestion] unique index
 * (face_id, cluster_id) makes this safe to re-run; existing pending suggestions are
 * refreshed with the latest similarity score.
 */
class PersonSuggestionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        /** Minimum cosine similarity (after any margin penalty) for a suggestion to be written. */
        const val SUGGESTION_THRESHOLD = 0.65f
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PandoraApp

        val settings = app.aiSettingsRepository.settings.first()
        if (!settings.faceProcessingEnabled) {
            Log.i(TAG, "Face processing disabled — worker exiting early")
            return@withContext Result.success()
        }

        val faceDao    = app.database.faceDao()
        val clusterDao = app.database.faceClusterDao()

        val activeEmbedder = app.modelManager.getActiveModel(ModelCategory.FACE_EMBEDDING)
        if (activeEmbedder == null) {
            Log.w(TAG, "No active face embedding model — aborting")
            return@withContext Result.failure(workDataOf("error" to "No face embedding model installed"))
        }
        val embedderVersion = activeEmbedder.metadata.roomVersionKey

        // Load confirmed clusters with a valid centroid
        val confirmedClusters = clusterDao.getConfirmedClusters()
            .filter { it.centroidBlob != null && it.n > 0 }
        if (confirmedClusters.isEmpty()) {
            Log.i(TAG, "No confirmed clusters — nothing to suggest")
            return@withContext Result.success(workDataOf("suggestionsWritten" to 0))
        }
        Log.i(TAG, "Matching against ${confirmedClusters.size} confirmed clusters")

        // Decode centroids once to avoid repeated ByteArray parsing
        val centroids = confirmedClusters.map { cluster ->
            cluster to EmbeddingUtils.bytesToFloatArray(cluster.centroidBlob!!)
        }

        // Load all face embeddings for the active embedder version.
        // Suggestions are generated for all faces (including already-assigned ones) so that the
        // UI can always show the best match. The REPLACE conflict strategy on the unique
        // (face_id, cluster_id) index keeps the table current on re-runs.
        val allRows = faceDao.getAllEmbeddingsForClustering(embedderVersion)

        val suggestions = mutableListOf<PersonSuggestion>()
        val now = System.currentTimeMillis()

        for (row in allRows) {
            if (!isActive) break
            val faceEmbedding = EmbeddingUtils.bytesToFloatArray(row.embedding)

            // ── Step 1: score against all confirmed clusters ──────────────────
            data class ClusterScore(val clusterId: String, val sim: Float)
            val scores = centroids
                .map { (cluster, centroid) ->
                    ClusterScore(cluster.clusterId, EmbeddingUtils.cosineSimilarity(faceEmbedding, centroid))
                }
                .sortedByDescending { it.sim }

            if (scores.isEmpty()) continue

            var topSim     = scores[0].sim
            val topCluster = scores[0].clusterId

            // ── Step 2: second-best margin rule ───────────────────────────────
            // If the gap between rank-1 and rank-2 is too small, the face sits in a region
            // equidistant between two cluster centroids — identity is ambiguous. Penalise
            // the top similarity proportionally to how small the gap is.
            if (scores.size >= 2) {
                val gap = topSim - scores[1].sim
                if (gap < SECOND_BEST_MARGIN) {
                    val penaltyFactor = gap / SECOND_BEST_MARGIN   // 0..1 linear decay
                    val penalised     = (topSim * penaltyFactor).coerceIn(0f, 1f)
                    if (penalised < SUGGESTION_THRESHOLD) {
                        Log.d(TAG, "Second-best margin suppressed face '${row.face_id}' " +
                            "(gap=${"%.3f".format(gap)}, penalised=${"%.3f".format(penalised)})")
                        continue   // suppress — too ambiguous
                    }
                    topSim = penalised
                }
            }

            // ── Step 3: write suggestion if above threshold ───────────────────
            if (topSim >= SUGGESTION_THRESHOLD) {
                suggestions += PersonSuggestion(
                    faceId    = row.face_id,
                    clusterId = topCluster,
                    similarity = topSim,
                    status    = PersonSuggestion.STATUS_PENDING,
                    createdAt = now
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            clusterDao.insertSuggestions(suggestions)
        }

        Log.i(TAG, "Person suggestions complete — ${suggestions.size} suggestions written")
        Result.success(workDataOf("suggestionsWritten" to suggestions.size))
    }
}
