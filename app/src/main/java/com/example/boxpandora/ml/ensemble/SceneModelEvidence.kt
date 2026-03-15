package com.example.boxpandora.ml.ensemble

/**
 * Normalized, per-model evidence for one (asset, tag) pair produced during an ensemble run.
 *
 * This is an **internal pipeline object** — it exists in memory only during a single
 * orchestration run and is persisted via [ModelInferenceEvidence] entities. It must
 * never be written directly into the UI-facing suggestion table.
 *
 * Alias resolution must happen *before* this object is created. [canonicalTagKey] must
 * always be the Tag.normalizedName, never a raw or alias form.
 *
 * @param assetId            URI of the media item.
 * @param canonicalTagKey    Alias-resolved, normalized tag name.
 * @param rawScore           Model-specific confidence score.
 * @param sourceModelId      Manifest ID of the model that produced this evidence.
 * @param sourceModelVersion Room version key (e.g. "scene_embedding:mobilenet_v3_scene-1.0.0").
 * @param scoreType          Scoring method; "cosine_prototype" for Phase 1.
 * @param inferenceTimestamp Wall-clock time of inference.
 */
data class SceneModelEvidence(
    val assetId: String,
    val canonicalTagKey: String,
    val rawScore: Float,
    val sourceModelId: String,
    val sourceModelVersion: String,
    val scoreType: String = "cosine_prototype",
    val inferenceTimestamp: Long = System.currentTimeMillis()
)
