package com.example.boxpandora.ml.ensemble

import android.util.Log
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.engine.TagSuggestionEngine
import com.example.boxpandora.ml.model.InstalledModel

private const val TAG = "DefaultSceneModelRunner"

/**
 * Concrete [SceneModelRunner] that wraps the existing [TagSuggestionEngine] pipeline
 * for one specific installed scene model.
 *
 * For each asset, this runner:
 *  1. Looks up the stored [ImageEmbedding] for this model's version key — no re-inference needed
 *     if the embedding already exists from a prior [SceneIndexWorker] run.
 *  2. Scores the embedding against that model's tag prototypes via [TagSuggestionEngine.score].
 *  3. Resolves tag aliases to canonical keys via the [TagAlias] / [Tag] tables.
 *  4. Returns the result as a list of [SceneModelEvidence] objects.
 *
 * If no embedding exists for this asset / model combination, the runner returns an empty list
 * without throwing — the orchestrator logs the miss and continues with other models.
 *
 * @param installed    The installed model to run evidence for.
 * @param isEnabled    Whether the user has enabled this model for ensemble execution.
 */
class DefaultSceneModelRunner(
    private val installed: InstalledModel,
    override val isEnabled: Boolean,
) : SceneModelRunner {

    override val modelId: String      = installed.metadata.id
    override val modelVersion: String = installed.metadata.roomVersionKey
    override val isInstalled: Boolean = true

    override suspend fun runForAsset(
        assetId: String,
        existingTagKeys: Set<String>,
        rejectedTagKeys: Set<String>,
        confidenceThreshold: Float,
        db: AppDatabase,
    ): List<SceneModelEvidence> {
        // 1. Load stored embedding for this model version
        val embeddingRow = db.imageEmbeddingDao().getForAssetAndModel(assetId, modelVersion)
        if (embeddingRow == null) {
            Log.d(TAG, "No embedding for asset=$assetId model=$modelId — skipping in ensemble run")
            return emptyList()
        }

        val assetEmbedding = EmbeddingUtils.bytesToFloatArray(embeddingRow.embedding)

        // 2. Load prototypes for this model version and score
        val engine = TagSuggestionEngine(
            tagPrototypeDao     = db.tagPrototypeDao(),
            tagDao              = db.tagDao(),
            tagCooccurrenceDao  = db.tagCooccurrenceDao(),
        )
        // Prototypes are shared across models in Phase 1 (single PK=tagKey in the table).
        // Each model's distinct embedding still produces meaningfully different scores.
        val prototypes = engine.loadPrototypes()
        if (prototypes.isEmpty()) {
            Log.d(TAG, "No prototypes for model=$modelId — skipping in ensemble run")
            return emptyList()
        }

        val scores = engine.score(
            assetEmbedding      = assetEmbedding,
            prototypes          = prototypes,
            existingTagKeys     = existingTagKeys,
            rejectedTagKeys     = rejectedTagKeys,
            confidenceThreshold = confidenceThreshold,
        )

        // 3. Resolve aliases to canonical tag keys and build evidence list
        val now = System.currentTimeMillis()
        val evidence = mutableListOf<SceneModelEvidence>()

        for (score in scores) {
            val canonicalKey = resolveCanonical(score.tagKey, db)
            evidence += SceneModelEvidence(
                assetId            = assetId,
                canonicalTagKey    = canonicalKey,
                rawScore           = score.finalConfidence,
                sourceModelId      = modelId,
                sourceModelVersion = modelVersion,
                scoreType          = "cosine_prototype",
                inferenceTimestamp = now,
            )
        }

        return evidence
    }

    /**
     * Resolves [tagKey] to a canonical tag normalized_name via the alias table.
     * Falls back to [tagKey] itself if no alias entry exists.
     */
    private suspend fun resolveCanonical(tagKey: String, db: AppDatabase): String {
        val alias = db.tagAliasDao().getByAlias(tagKey) ?: return tagKey
        val tag   = db.tagDao().getById(alias.tagId) ?: return tagKey
        return tag.normalizedName
    }
}
