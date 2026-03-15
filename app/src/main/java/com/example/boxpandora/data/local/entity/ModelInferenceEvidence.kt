package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw per-model scene tag evidence produced by each participating model in an ensemble run.
 *
 * This is an **internal pipeline record** — it is never exposed directly to the UI.
 * The [SuggestionFusionEngine] reads these rows, fuses them, and writes results to
 * [FusedSceneSuggestion]. Provenance is preserved here for debugging and future phases.
 *
 * [assetId]           – URI of the media item this evidence was produced for.
 * [canonicalTagKey]   – normalized tag name after alias resolution; always canonical before write.
 * [rawScore]          – model-specific confidence score (cosine similarity or similar).
 * [modelId]           – manifest ID of the model that produced this evidence.
 * [modelVersion]      – Room version key (e.g. "scene_embedding:mobilenet_v3_scene-1.0.0").
 * [scoreType]         – how the score was computed; "cosine_prototype" for Phase 1.
 * [pipelineRunId]     – groups all evidence rows produced in the same ensemble orchestration run.
 * [inferredAt]        – wall-clock timestamp of inference.
 */
@Entity(
    tableName = "model_inference_evidence",
    foreignKeys = [
        ForeignKey(
            entity = MediaItem::class,
            parentColumns = ["uri"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("asset_id"),
        Index("pipeline_run_id"),
        Index(value = ["asset_id", "model_id", "canonical_tag_key"])
    ]
)
data class ModelInferenceEvidence(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "asset_id")
    val assetId: String,

    @ColumnInfo(name = "canonical_tag_key")
    val canonicalTagKey: String,

    @ColumnInfo(name = "raw_score")
    val rawScore: Float,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    @ColumnInfo(name = "model_version")
    val modelVersion: String,

    @ColumnInfo(name = "score_type")
    val scoreType: String = "cosine_prototype",

    @ColumnInfo(name = "pipeline_run_id")
    val pipelineRunId: String,

    @ColumnInfo(name = "inferred_at")
    val inferredAt: Long = System.currentTimeMillis()
)
