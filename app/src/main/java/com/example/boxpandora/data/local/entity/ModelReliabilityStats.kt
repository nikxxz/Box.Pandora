package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted per-model reliability record for ensemble fusion weighting.
 *
 * One row per (model_id, pipeline_category, tag_category) combination. A scene model
 * can therefore have different reliability records for "animal" vs "mood", reflecting
 * that model performance varies across content domains.
 *
 * The unique key is (model_id, pipeline_category, tag_category) — model_version is
 * recorded as a best-effort provenance field but is not part of the key. Reliability
 * reputation is intentionally preserved across minor version bumps so that a model's
 * track record is not wiped every time it is updated.
 *
 * [derivedWeight] is recomputed by [ReliabilityUpdateService] after each accept/reject
 * event using Bayesian-smoothed precision. It approaches 1.0 with neutral performance
 * and drifts toward [FusionThresholdConfig.WEIGHT_MAX] / [WEIGHT_MIN] only with
 * sustained consistent feedback and at least [FusionThresholdConfig.MIN_EVENTS_FOR_WEIGHT]
 * real events accumulated.
 *
 * [isCoolingDown] is reserved for future burst-rejection detection. Not yet active.
 */
@Entity(
    tableName = "model_reliability_stats",
    indices = [
        Index(
            value  = ["model_id", "pipeline_category", "tag_category"],
            unique = true,
        ),
        Index("model_id"),
    ]
)
data class ModelReliabilityStats(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")                val id: Long = 0,

    @ColumnInfo(name = "model_id")          val modelId: String,

    /** Latest known model version; updated on each write. Not part of unique key. */
    @ColumnInfo(name = "model_version")     val modelVersion: String = "",

    /** "scene_embedding", "face_embedding", or "face_detection". */
    @ColumnInfo(name = "pipeline_category") val pipelineCategory: String,

    /**
     * For scene models: the tag category this row tracks (e.g. "animal", "mood").
     * For face recognizers: always "identity".
     * Never mix scene-tag and identity semantics in the same row.
     */
    @ColumnInfo(name = "tag_category")      val tagCategory: String,

    @ColumnInfo(name = "accepted_count")    val acceptedCount: Int = 0,
    @ColumnInfo(name = "rejected_count")    val rejectedCount: Int = 0,
    @ColumnInfo(name = "surfaced_count")    val totalSurfacedCount: Int = 0,

    @ColumnInfo(name = "last_updated_at")   val lastUpdatedAt: Long = System.currentTimeMillis(),

    /**
     * Pre-computed fusion weight in range [[FusionThresholdConfig.WEIGHT_MIN],
     * [FusionThresholdConfig.WEIGHT_MAX]]. Default 1.0 (neutral) until at least
     * [FusionThresholdConfig.MIN_EVENTS_FOR_WEIGHT] feedback events accumulate.
     */
    @ColumnInfo(name = "derived_weight")    val derivedWeight: Float = 1.0f,

    /** Reserved: temporary downweight flag for burst-rejection detection. */
    @ColumnInfo(name = "is_cooling_down")   val isCoolingDown: Boolean = false,
) {
    companion object {
        const val PIPELINE_SCENE    = "scene_embedding"
        const val PIPELINE_IDENTITY = "face_embedding"
        const val TAG_CAT_IDENTITY  = "identity"
    }
}
