package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.boxpandora.ml.ensemble.AgreementLevel
import com.example.boxpandora.ml.ensemble.toLabel

/**
 * A fused person-identity suggestion produced by [IdentityFusionEngine] in ensemble mode.
 *
 * Each row is a candidate identity (person tag) for a specific fused face. The score is
 * derived by averaging per-recognizer cosine similarities plus an agreement bonus.
 *
 * Status lifecycle:
 *   [STATUS_PENDING]  — awaiting user review.
 *   [STATUS_ACCEPTED] — user confirmed this identity for the face.
 *   [STATUS_REJECTED] — user rejected this identity; will not be re-suggested.
 *   [STATUS_APPLIED]  — identity was applied as a person tag on the asset.
 *
 * [isAmbiguous] is set when the second-best margin rule fires, indicating the model is not
 * confident and the user should verify carefully.
 *
 * [agreementLevelOrdinal] is the [AgreementLevel] ordinal (0=LOW, 1=MEDIUM, 2=HIGH) computed
 * at fusion time from recognizer count, score spread, ambiguity, and consensus ratio.
 * Exposed via the [agreementLevel] computed property.
 */
@Entity(
    tableName = "fused_identity_suggestions",
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
        Index("fused_face_id"),
        Index(value = ["fused_face_id", "cluster_id"], unique = true),
    ]
)
data class FusedIdentitySuggestion(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")                    val id: Long = 0,
    @ColumnInfo(name = "asset_id")              val assetId: String,
    @ColumnInfo(name = "fused_face_id")         val fusedFaceId: String,
    @ColumnInfo(name = "cluster_id")            val clusterId: String,
    @ColumnInfo(name = "tag_key")               val tagKey: String,
    @ColumnInfo(name = "tag_name")              val tagName: String,
    @ColumnInfo(name = "tag_id")                val tagId: Long,
    @ColumnInfo(name = "fused_score")           val fusedScore: Float,
    /** CSV of recognizer IDs that contributed a score for this identity. */
    @ColumnInfo(name = "contributing_recognizer_ids")   val contributingRecognizerIds: String,
    @ColumnInfo(name = "contributing_recognizer_count") val contributingRecognizerCount: Int,
    @ColumnInfo(name = "strongest_score")       val strongestScore: Float,
    @ColumnInfo(name = "is_ambiguous")          val isAmbiguous: Boolean = false,
    /**
     * [AgreementLevel] ordinal (0=LOW, 1=MEDIUM, 2=HIGH).
     * Computed at fusion time; exposed via the [agreementLevel] computed property.
     */
    @ColumnInfo(name = "agreement_level")       val agreementLevelOrdinal: Int = 0,
    @ColumnInfo(name = "status")                val status: String = STATUS_PENDING,
    @ColumnInfo(name = "pipeline_run_id")       val pipelineRunId: String,
    @ColumnInfo(name = "created_at")            val createdAt: Long = System.currentTimeMillis(),
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
        get() = agreementLevel.toLabel(contributingRecognizerCount)

    companion object {
        const val STATUS_PENDING  = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_APPLIED  = "applied"
    }
}
