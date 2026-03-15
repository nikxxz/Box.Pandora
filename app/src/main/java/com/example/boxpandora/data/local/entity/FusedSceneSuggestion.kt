package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.boxpandora.ml.ensemble.AgreementLevel
import com.example.boxpandora.ml.ensemble.toLabel

/**
 * Final fused scene tag suggestion produced by [SuggestionFusionEngine] in ensemble mode.
 *
 * This is the **UI-facing** output of the ensemble pipeline. The repository layer returns
 * rows from this table when [AiPipelineMode.ENSEMBLE_ALL_ENABLED] is active, and from
 * [TagSuggestion] in [AiPipelineMode.SINGLE_ACTIVE] mode — callers do not need to know
 * which source was used.
 *
 * [assetId]                – URI of the media item.
 * [canonicalTagKey]        – normalized, alias-resolved tag name.
 * [tagCategory]            – content category resolved from [Tag.category]; "misc" if unknown.
 * [fusedScore]             – combined confidence after reliability-weighted fusion + agreement bonus.
 * [contributingModelIds]   – comma-separated manifest IDs of models that produced evidence.
 * [contributingModelCount] – number of models that contributed (denormalised for UI badge).
 * [strongestScore]         – highest raw score among contributing models (provenance).
 * [isAmbiguous]            – true when this tag leads the second-best in its category by less than
 *                            [FusionThresholdConfig.CategoryThresholds.secondBestMargin].
 * [agreementLevelOrdinal]  – [AgreementLevel] ordinal (0=LOW, 1=MEDIUM, 2=HIGH), computed at
 *                            fusion time from model count, score spread, ambiguity, and consensus
 *                            ratio. Exposed as [agreementLevel] (computed property).
 * [status]                 – "pending" until the user acts on it; reserved for future states.
 * [pipelineRunId]          – links this row back to the [ModelInferenceEvidence] batch.
 * [createdAt]              – wall-clock timestamp of fusion.
 */
@Entity(
    tableName = "fused_scene_suggestions",
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
        Index(value = ["asset_id", "canonical_tag_key"], unique = true)
    ]
)
data class FusedSceneSuggestion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "asset_id")
    val assetId: String,

    @ColumnInfo(name = "canonical_tag_key")
    val canonicalTagKey: String,

    /** Content category resolved from [Tag.category]; "misc" if the tag key was not found in DB. */
    @ColumnInfo(name = "tag_category")
    val tagCategory: String = "misc",

    @ColumnInfo(name = "fused_score")
    val fusedScore: Float,

    /** CSV of model IDs, e.g. "mobilenet_v3_scene,efficientnet_lite4_scene". */
    @ColumnInfo(name = "contributing_model_ids")
    val contributingModelIds: String,

    @ColumnInfo(name = "contributing_model_count")
    val contributingModelCount: Int,

    @ColumnInfo(name = "strongest_score")
    val strongestScore: Float,

    /**
     * True when this tag's fused score leads the second-best in the same category by less than
     * [FusionThresholdConfig.CategoryThresholds.secondBestMargin]. Surfaced as a UI hint.
     */
    @ColumnInfo(name = "is_ambiguous")
    val isAmbiguous: Boolean = false,

    /**
     * [AgreementLevel] ordinal (0=LOW, 1=MEDIUM, 2=HIGH).
     * Computed at fusion time from model count, score spread, ambiguity, and consensus ratio.
     * Exposed via the [agreementLevel] computed property.
     */
    @ColumnInfo(name = "agreement_level")
    val agreementLevelOrdinal: Int = 0,

    @ColumnInfo(name = "status")
    val status: String = "pending",

    @ColumnInfo(name = "pipeline_run_id")
    val pipelineRunId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Agreement level decoded from [agreementLevelOrdinal]. Not stored directly. */
    val agreementLevel: AgreementLevel
        get() = when (agreementLevelOrdinal) {
            1    -> AgreementLevel.MEDIUM
            2    -> AgreementLevel.HIGH
            else -> AgreementLevel.LOW
        }

    /** Compact UI label, e.g. "1 model", "2 models", "High agreement". */
    val agreementLabel: String
        get() = agreementLevel.toLabel(contributingModelCount)

    /**
     * The first model ID from [contributingModelIds], or null if the CSV is blank.
     * Useful for a "best model" provenance hint in the UI.
     */
    val topContributingModelId: String?
        get() = contributingModelIds.split(",").firstOrNull { it.isNotBlank() }
}
