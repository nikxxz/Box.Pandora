package com.example.boxpandora.ml.clustering

import android.util.Log
import com.example.boxpandora.data.local.entity.FaceCluster
import com.example.boxpandora.data.local.entity.FaceClusterCorrection
import com.example.boxpandora.ml.engine.EmbeddingUtils
import java.util.UUID

private const val TAG = "FaceClusterEngine"

/**
 * Conservative centroid-based face clustering.
 *
 * Design principles:
 *  - Prefer under-merging over over-merging: it is better to produce two clusters for the
 *    same person than one cluster shared across different people.
 *  - User corrections are hard constraints respected before any automatic logic.
 *  - Full rebuild on every run ensures consistency when corrections change.
 *
 * Algorithm:
 *  1. Faces are processed in descending quality-score order (high-confidence detections first).
 *  2. For each face, apply user corrections first:
 *     - action='assign'  → force-assign to the specified cluster.
 *     - action='exclude' → skip this face (leave cluster_id null).
 *  3. For uncorrected faces, compute cosine similarity with every known cluster centroid.
 *  4. If the best similarity ≥ [MERGE_THRESHOLD], assign to that cluster; otherwise create a
 *     new cluster.
 *  5. After assignment, update the cluster centroid as a running L2-normalised mean.
 *
 * @param MERGE_THRESHOLD Minimum centroid similarity required to join an existing cluster.
 *   0.72 is conservative — equivalent to ~44° angle between embedding vectors. Tested values:
 *   0.75+ (very strict, many singletons), 0.70 (moderate), 0.65 (permissive/risky).
 */
class FaceClusterEngine(
    private val mergeThreshold: Float = MERGE_THRESHOLD
) {

    companion object {
        const val MERGE_THRESHOLD = 0.72f
    }

    /**
     * Input record for one face.
     */
    data class FaceInput(
        val faceId: String,
        val embedding: FloatArray,
        val qualityScore: Double
    )

    /**
     * Result of the clustering run.
     */
    data class ClusterResult(
        /** Updated cluster states (centroid, n, metadata preserved from existing clusters). */
        val clusters: List<FaceCluster>,
        /** Map of faceId → clusterId for all assigned faces. Excluded faces are absent. */
        val assignments: Map<String, String>
    )

    /**
     * Runs a full clustering rebuild.
     *
     * @param faces         All faces with embeddings, sorted by qualityScore DESC.
     * @param existingClusters All clusters currently in the database (with metadata to preserve).
     * @param corrections   All user corrections — the latest correction per face wins.
     * @param now           Timestamp to write into updated_at fields.
     */
    fun run(
        faces: List<FaceInput>,
        existingClusters: List<FaceCluster>,
        corrections: List<FaceClusterCorrection>,
        now: Long = System.currentTimeMillis()
    ): ClusterResult {
        // Build a map of the latest correction per faceId
        val latestCorrection: Map<String, FaceClusterCorrection> =
            corrections.groupBy { it.faceId }
                .mapValues { (_, list) -> list.maxBy { it.createdAt } }

        // Mutable cluster states (preserve existing metadata: name, confirmedByUser, isHidden, tagId)
        val clusterStates = mutableMapOf<String, MutableClusterState>()
        for (c in existingClusters) {
            if (c.centroidBlob != null) {
                clusterStates[c.clusterId] = MutableClusterState(
                    clusterId       = c.clusterId,
                    centroid        = EmbeddingUtils.bytesToFloatArray(c.centroidBlob),
                    n               = 0, // reset; will be rebuilt
                    name            = c.name,
                    confirmedByUser = c.confirmedByUser,
                    isHidden        = c.isHidden,
                    tagId           = c.tagId,
                    createdAt       = c.createdAt
                )
            }
        }
        // Track clusters that have no centroid yet (existed but never got faces — keep metadata)
        val noCentroidClusters = existingClusters
            .filter { it.centroidBlob == null }
            .associateBy { it.clusterId }

        val assignments = mutableMapOf<String, String>()

        for (face in faces) {
            val correction = latestCorrection[face.faceId]

            when {
                // ── User excluded this face from clustering ──────────────────
                correction?.action == FaceClusterCorrection.ACTION_EXCLUDE -> {
                    // Leave cluster_id null; do not assign
                    Log.v(TAG, "Excluded by correction: ${face.faceId}")
                }

                // ── User forced this face into a specific cluster ────────────
                correction?.action == FaceClusterCorrection.ACTION_ASSIGN &&
                        correction.toClusterId != null -> {
                    val targetId = correction.toClusterId
                    val state = clusterStates.getOrPut(targetId) {
                        // Target cluster may not exist yet if user typed a custom clusterId;
                        // create a minimal state.
                        MutableClusterState(
                            clusterId = targetId,
                            centroid  = face.embedding.copyOf(),
                            n         = 0,
                            name      = noCentroidClusters[targetId]?.name,
                            confirmedByUser = noCentroidClusters[targetId]?.confirmedByUser ?: false,
                            isHidden  = noCentroidClusters[targetId]?.isHidden ?: false,
                            tagId     = noCentroidClusters[targetId]?.tagId,
                            createdAt = noCentroidClusters[targetId]?.createdAt ?: now
                        )
                    }
                    state.addFace(face.embedding)
                    assignments[face.faceId] = targetId
                    Log.v(TAG, "Assigned by correction: ${face.faceId} → $targetId")
                }

                // ── Automatic assignment ─────────────────────────────────────
                else -> {
                    val bestMatch = findBestCluster(face.embedding, clusterStates)
                    val clusterId = if (bestMatch != null && bestMatch.second >= mergeThreshold) {
                        bestMatch.first.also { id ->
                            clusterStates[id]!!.addFace(face.embedding)
                        }
                    } else {
                        // Start a new cluster
                        val newId = UUID.randomUUID().toString()
                        clusterStates[newId] = MutableClusterState(
                            clusterId       = newId,
                            centroid        = face.embedding.copyOf(),
                            n               = 1,
                            name            = null,
                            confirmedByUser = false,
                            isHidden        = false,
                            tagId           = null,
                            createdAt       = now
                        )
                        newId
                    }
                    assignments[face.faceId] = clusterId
                }
            }
        }

        // Build output FaceCluster entities
        val resultClusters = mutableListOf<FaceCluster>()

        // Clusters that received faces
        for ((id, state) in clusterStates) {
            resultClusters += state.toEntity(now)
        }

        // Clusters that already existed but received no faces this run — preserve with n=0
        for ((id, existing) in noCentroidClusters) {
            if (id !in clusterStates) {
                resultClusters += existing.copy(n = 0, updatedAt = now)
            }
        }

        Log.i(TAG, "Clustering done — ${faces.size} faces → ${clusterStates.size} active clusters")
        return ClusterResult(clusters = resultClusters, assignments = assignments)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun findBestCluster(
        embedding: FloatArray,
        states: Map<String, MutableClusterState>
    ): Pair<String, Float>? {
        if (states.isEmpty()) return null
        var bestId: String? = null
        var bestSim = Float.NEGATIVE_INFINITY
        for ((id, state) in states) {
            if (state.n == 0) continue // skip clusters that have no faces assigned yet this run
            val sim = EmbeddingUtils.cosineSimilarity(embedding, state.centroid)
            if (sim > bestSim) {
                bestSim = sim
                bestId = id
            }
        }
        return bestId?.let { it to bestSim }
    }

    // ── Mutable state during a run ────────────────────────────────────────────

    private inner class MutableClusterState(
        val clusterId: String,
        centroid: FloatArray,
        var n: Int,
        val name: String?,
        val confirmedByUser: Boolean,
        val isHidden: Boolean,
        val tagId: Long?,
        val createdAt: Long
    ) {
        // Keep an unnormalised accumulator so we can compute the normalised mean cheaply
        private val accumulator: FloatArray = centroid.copyOf().also { v ->
            // If n > 0 the initial centroid is already normalised; scale back up
            if (n > 0) for (i in v.indices) v[i] *= n
        }
        var centroid: FloatArray = centroid.copyOf()
            private set

        fun addFace(embedding: FloatArray) {
            n++
            for (i in accumulator.indices) accumulator[i] += embedding[i]
            // Recompute normalised centroid
            val mean = accumulator.copyOf().also { a -> for (i in a.indices) a[i] /= n }
            centroid = EmbeddingUtils.l2Normalize(mean)
        }

        fun toEntity(now: Long) = FaceCluster(
            clusterId       = clusterId,
            centroidBlob    = EmbeddingUtils.floatArrayToBytes(centroid),
            dim             = centroid.size,
            n               = n,
            tagId           = tagId,
            name            = name,
            confirmedByUser = confirmedByUser,
            isHidden        = isHidden,
            createdAt       = createdAt,
            updatedAt       = now
        )
    }
}
