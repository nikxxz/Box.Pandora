package com.example.boxpandora.ml.ensemble

import com.example.boxpandora.data.local.AppDatabase

/**
 * Abstraction over a single scene model that can produce normalized tag evidence for one asset.
 *
 * Every scene model participating in ensemble mode must implement this interface so
 * [SceneEnsembleOrchestrator] can call multiple runners uniformly without knowing about
 * specific model implementations.
 *
 * Phase 1 does not require rewriting existing model implementations. The
 * [DefaultSceneModelRunner] wraps the current [SceneEmbeddingService] +
 * [TagSuggestionEngine] pipeline behind this interface.
 *
 * @property modelId      Manifest ID, e.g. "mobilenet_v3_scene".
 * @property modelVersion Room version key, e.g. "scene_embedding:mobilenet_v3_scene-1.0.0".
 * @property isInstalled  Whether the model binary is present and ready to run.
 * @property isEnabled    Whether the user has enabled this model for ensemble execution.
 */
interface SceneModelRunner {

    val modelId: String
    val modelVersion: String
    val isInstalled: Boolean
    val isEnabled: Boolean

    /**
     * Produces [SceneModelEvidence] for [assetId] using the model this runner wraps.
     *
     * The implementation must:
     * 1. Retrieve or compute the embedding for [assetId] (use cached DB row when available).
     * 2. Score the embedding against this model's tag prototypes.
     * 3. Resolve tag aliases to canonical tag keys *before* populating [SceneModelEvidence].
     * 4. Return an empty list (not throw) if no prototypes exist or the asset has no embedding.
     *
     * @param assetId            URI of the media item to process.
     * @param existingTagKeys    Tags already applied — excluded from evidence to avoid noise.
     * @param rejectedTagKeys    Tags the user has previously rejected — excluded from evidence.
     * @param confidenceThreshold Minimum raw score; evidence below this is dropped before return.
     * @param db                 Database handle for embedding and prototype lookups.
     * @return                   List of normalized evidence objects, one per candidate tag;
     *                           empty if no qualifying evidence was produced.
     */
    suspend fun runForAsset(
        assetId: String,
        existingTagKeys: Set<String>,
        rejectedTagKeys: Set<String>,
        confidenceThreshold: Float,
        db: AppDatabase,
    ): List<SceneModelEvidence>
}
