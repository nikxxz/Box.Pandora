package com.example.boxpandora.ml.engine

import android.util.Log
import com.example.boxpandora.data.local.dao.FaceClusterDao
import com.example.boxpandora.data.local.dao.FaceDao
import com.example.boxpandora.data.local.dao.TagDao
import com.example.boxpandora.data.local.entity.FaceCluster

private const val TAG = "PersonProfileEngine"

/** Minimum face embeddings required to build a meaningful centroid for a person. */
private const val MIN_SAMPLES = 1

/**
 * Builds or updates per-person [FaceCluster] rows from confirmed, high-trust training samples.
 *
 * **Learning sources** — only two sources are considered confirmed enough to train from:
 *
 *  1. **Tag-derived** — images that have exactly one detected face AND exactly one person-category
 *     tag. The single-face constraint guarantees the face belongs to the labelled person; the
 *     single-person-tag constraint prevents ambiguous multi-person images from mixing into a
 *     profile. Queried via [FaceDao.getSingleFaceSinglePersonTagSamples].
 *
 *  2. **Correction-derived** — faces the user has explicitly moved into a cluster that carries
 *     a person tag. User corrections are the strongest available signal. Queried via
 *     [FaceDao.getCorrectionDerivedPersonSamples]. Corrections override tag-derived samples
 *     when the same face appears in both sets.
 *
 * **What is never used:**
 *  - Faces assigned by the automatic clustering algorithm (unconfirmed).
 *  - Pending [PersonSuggestion] rows (user has not yet reviewed them).
 *  - Multi-face images (who the person tag applies to is ambiguous).
 *  - Multi-person-tag images (which tag is the correct identity is ambiguous).
 *
 * **Output:**
 *  One [FaceCluster] per person tag, marked [FaceCluster.confirmedByUser] = true, linked via
 *  [FaceCluster.tagId]. This makes them immediately eligible for [PersonSuggestionWorker]
 *  matching without any additional UI confirmation step.
 *
 *  On each run, the centroid and sample count are fully replaced. Non-centroid metadata
 *  (is_hidden, created_at) is preserved from the existing cluster row if one already exists.
 *
 * **Idempotency:** Safe to run multiple times. Each run is a complete rebuild of all
 * person-tag-derived clusters. Clusters for person tags that no longer have qualifying
 * samples are left in place (they may still serve as UI anchors), but their centroid and n
 * will be stale — callers can detect this because n will become 0 after the next run if
 * samples disappear.
 */
class PersonProfileEngine(
    private val faceDao: FaceClusterDao,
    private val taggedFaceDao: FaceDao,
    private val tagDao: TagDao
) {

    /**
     * Rebuilds all person-profile clusters from confirmed training samples.
     *
     * @param embedderVersion  Room version key of the active face embedding model.
     *                         Only face embeddings produced by this model version are used.
     * @return Number of [FaceCluster] rows written (created or updated).
     */
    suspend fun rebuildAll(embedderVersion: String): Int {
        // ── 1. Collect training samples from both high-trust sources ──────────
        val tagSamples        = taggedFaceDao.getSingleFaceSinglePersonTagSamples(embedderVersion)
        val correctionSamples = taggedFaceDao.getCorrectionDerivedPersonSamples(embedderVersion)

        // Merge: correction-confirmed samples override tag-derived ones for the same face_id.
        // This ensures that if a user explicitly corrects the identity of a face, the
        // correction takes precedence over what the tag on the image says.
        val byFaceId = LinkedHashMap<String, FaceDao.PersonTrainingSample>(tagSamples.size + correctionSamples.size)
        for (s in tagSamples)        byFaceId[s.face_id] = s
        for (s in correctionSamples) byFaceId[s.face_id] = s   // corrections win

        if (byFaceId.isEmpty()) {
            Log.i(TAG, "No high-trust person training samples found — no profiles written")
            return 0
        }

        // ── 2. Group samples by person tag ────────────────────────────────────
        val byTagId = mutableMapOf<Long, MutableList<FaceDao.PersonTrainingSample>>()
        for (sample in byFaceId.values) {
            byTagId.getOrPut(sample.tag_id) { mutableListOf() }.add(sample)
        }

        Log.i(TAG, "Building profiles: ${byTagId.size} person tags, ${byFaceId.size} total samples")

        // ── 3. Build one cluster per person tag ───────────────────────────────
        var written = 0
        for ((tagId, samples) in byTagId) {
            if (samples.size < MIN_SAMPLES) continue

            val tag = tagDao.getById(tagId)
            if (tag == null) {
                Log.w(TAG, "Tag $tagId not found — skipping")
                continue
            }

            val embeddings = samples.map { EmbeddingUtils.bytesToFloatArray(it.embedding) }
            val centroid   = EmbeddingUtils.normalizedMean(embeddings) ?: continue
            val centroidBytes = EmbeddingUtils.floatArrayToBytes(centroid)

            // Preserve non-centroid metadata from the existing cluster (if any).
            val existing  = faceDao.getClusterForTag(tagId)
            val clusterId = existing?.clusterId ?: java.util.UUID.randomUUID().toString()

            faceDao.insertCluster(
                FaceCluster(
                    clusterId       = clusterId,
                    centroidBlob    = centroidBytes,
                    dim             = centroid.size,
                    n               = samples.size,
                    tagId           = tagId,
                    name            = tag.name,
                    confirmedByUser = true,
                    isHidden        = existing?.isHidden ?: false,
                    createdAt       = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt       = System.currentTimeMillis()
                )
            )

            Log.d(TAG, "Profile written: '${tag.name}' — ${samples.size} samples, cluster=$clusterId")
            written++
        }

        return written
    }
}
