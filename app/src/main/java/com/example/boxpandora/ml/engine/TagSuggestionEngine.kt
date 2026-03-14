package com.example.boxpandora.ml.engine

import android.util.Log
import com.example.boxpandora.data.local.dao.TagCooccurrenceDao
import com.example.boxpandora.data.local.dao.TagDao
import com.example.boxpandora.data.local.dao.TagPrototypeDao
import com.example.boxpandora.data.local.entity.TagPrototype

private const val TAG = "TagSuggestionEngine"

/** Co-occurrence boost applied per step of related-tag hit. Kept intentionally small. */
private const val COOCCURRENCE_BOOST_PER_HIT = 0.04f

/** Maximum total co-occurrence boost. Caps the secondary signal so prototype score dominates. */
private const val MAX_COOCCURRENCE_BOOST = 0.12f

/**
 * Minimum gap between the top-1 and top-2 finalConfidence before the second-best margin
 * rule fires. If the gap is smaller than this, the top suggestion is ambiguous (the asset
 * embedding lies too close to two prototype vectors) and confidence is penalised
 * proportionally: `penaltyFactor = gap / SECOND_BEST_MARGIN`.
 *
 * This only affects the single most-confident candidate (rank 1); lower-ranked suggestions
 * are left intact — a legitimate multi-tag image can still produce multiple results.
 */
const val SECOND_BEST_MARGIN = 0.08f

/**
 * Scores an asset's embedding against all available tag prototypes and returns a ranked list
 * of [SuggestionScore] candidates.
 *
 * Scoring pipeline per asset:
 *   1. Dot-product (≡ cosine similarity since both vectors are L2-normalized) against each prototype.
 *   2. Optional co-occurrence boost: for each of the top-5 prototype matches above [boostThreshold],
 *      look up tags that co-occur with it and apply a small additive boost.
 *   3. Filter by [confidenceThreshold], sort descending, cap at [maxResults].
 *   4. Second-best margin rule: if the gap between rank-1 and rank-2 finalConfidence is less
 *      than [SECOND_BEST_MARGIN], penalise the rank-1 candidate proportionally. If after
 *      the penalty its confidence falls below [confidenceThreshold], it is suppressed.
 *      The penalty amount is recorded in [SuggestionScore.marginPenalty].
 *
 * All I/O (prototype loading, co-occurrence lookup) must be done before calling [score] —
 * the scoring function itself is pure and runs on whatever thread the caller uses.
 *
 * Supported media types: images, GIFs, videos — any asset that has a scene embedding in
 * [image_embeddings] can be scored, regardless of media type.
 *
 * Typical usage from a worker:
 *   1. Call [loadPrototypes] once per run.
 *   2. For each asset, call [score].
 */
class TagSuggestionEngine(
    private val tagPrototypeDao: TagPrototypeDao,
    private val tagDao: TagDao,
    private val tagCooccurrenceDao: TagCooccurrenceDao
) {

    data class LoadedPrototype(
        val tagKey: String,
        val vector: FloatArray,
        val sampleCount: Int,
        val modelVersion: String
    )

    /**
     * Loads all prototypes into memory. Call once per worker run, share across all asset scores.
     * Returns an empty list if no prototypes exist yet.
     */
    suspend fun loadPrototypes(): List<LoadedPrototype> {
        val rows: List<TagPrototype> = tagPrototypeDao.getAll()
        Log.i(TAG, "Loaded ${rows.size} prototypes")
        return rows.map { row ->
            LoadedPrototype(
                tagKey       = row.tagKey,
                vector       = EmbeddingUtils.bytesToFloatArray(row.prototypeBlob),
                sampleCount  = row.n,
                modelVersion = row.modelVersion
            )
        }
    }

    /**
     * Scores [assetEmbedding] against [prototypes] and returns candidates above
     * [confidenceThreshold] sorted by finalConfidence descending.
     *
     * @param existingTagKeys  Tags already applied to this asset — excluded from output.
     * @param rejectedTagKeys  Tags previously rejected by the user — excluded from output.
     */
    suspend fun score(
        assetEmbedding: FloatArray,
        prototypes: List<LoadedPrototype>,
        existingTagKeys: Set<String>,
        rejectedTagKeys: Set<String>,
        confidenceThreshold: Float,
        maxResults: Int = 20
    ): List<SuggestionScore> {
        if (prototypes.isEmpty()) return emptyList()

        // ── Step 1: compute base cosine similarities ─────────────────────────
        data class RawScore(val proto: LoadedPrototype, val sim: Float)
        val rawScores = prototypes.map { proto ->
            RawScore(proto, EmbeddingUtils.cosineSimilarity(assetEmbedding, proto.vector))
        }.sortedByDescending { it.sim }

        // ── Step 2: co-occurrence boost ───────────────────────────────────────
        // Look at the top-5 raw matches above a relaxed threshold and apply a small
        // additive boost to tags that frequently co-occur with those matches.
        val boostMap = mutableMapOf<String, Float>()
        val boostThreshold = (confidenceThreshold * 0.6f).coerceAtLeast(0.2f)
        val topForBoost = rawScores.take(5).filter { it.sim >= boostThreshold }

        for (topMatch in topForBoost) {
            val tag = tagDao.getByNormalizedName(topMatch.proto.tagKey) ?: continue
            val relatedKeys = tagCooccurrenceDao.getRelatedTagKeys(tag.id, limit = 8)
            val weight = topMatch.sim
            for (related in relatedKeys) {
                val current  = boostMap.getOrDefault(related.tagKey, 0f)
                val addition = weight * (related.coCount.toFloat() / (related.coCount + 10f)) *
                               COOCCURRENCE_BOOST_PER_HIT
                boostMap[related.tagKey] = (current + addition).coerceAtMost(MAX_COOCCURRENCE_BOOST)
            }
        }

        // ── Step 3: build final scores, exclude applied/rejected, filter, sort ─
        val candidates = rawScores
            .filter { it.proto.tagKey !in existingTagKeys && it.proto.tagKey !in rejectedTagKeys }
            .mapNotNull { raw ->
                val boost = boostMap.getOrDefault(raw.proto.tagKey, 0f)
                val final = (raw.sim + boost).coerceIn(0f, 1f)
                if (final < confidenceThreshold) null
                else SuggestionScore(
                    tagKey            = raw.proto.tagKey,
                    baseSimilarity    = raw.sim,
                    cooccurrenceBoost = boost,
                    finalConfidence   = final,
                    source            = if (boost > 0f) "scene_prototype+cooccurrence"
                                        else "scene_prototype",
                    sampleCount       = raw.proto.sampleCount,
                    marginPenalty     = 0f
                )
            }
            .sortedByDescending { it.finalConfidence }
            .take(maxResults)

        // ── Step 4: second-best margin rule ───────────────────────────────────
        // If the top suggestion's finalConfidence is not clearly dominant over the second
        // suggestion (gap < SECOND_BEST_MARGIN), it is ambiguous: the asset embedding lies
        // in a region where two prototypes are nearly equidistant. Penalise the top candidate
        // proportionally to how small the gap is. Candidates at rank 2+ are unaffected so
        // that legitimate multi-tag images are not suppressed.
        if (candidates.size < 2) return candidates

        val gap = candidates[0].finalConfidence - candidates[1].finalConfidence
        if (gap >= SECOND_BEST_MARGIN) return candidates

        val penaltyFactor    = gap / SECOND_BEST_MARGIN   // 0..1, linear decay
        val originalTop      = candidates[0].finalConfidence
        val penalisedConf    = (originalTop * penaltyFactor).coerceIn(0f, 1f)
        val penalty          = originalTop - penalisedConf

        return if (penalisedConf < confidenceThreshold) {
            // Top candidate falls below threshold after penalty — suppress it
            Log.d(TAG, "Second-best margin suppressed '${candidates[0].tagKey}' " +
                "(gap=${"%.3f".format(gap)}, penalisedConf=${"%.3f".format(penalisedConf)})")
            candidates.drop(1)
        } else {
            // Retain top candidate with reduced confidence and recorded penalty
            val penalised = candidates[0].copy(
                finalConfidence = penalisedConf,
                marginPenalty   = penalty
            )
            listOf(penalised) + candidates.drop(1)
        }
    }
}
