package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw per-recognizer identity evidence for a single fused face.
 *
 * Produced by each enabled face recognizer during ensemble face processing; one row per
 * (fused_face_id, recognizer_id, cluster_id) triple. Consumed by [IdentityFusionEngine]
 * to produce [FusedIdentitySuggestion] rows.
 *
 * Internal pipeline record — not surfaced directly to the UI.
 */
@Entity(
    tableName = "identity_inference_evidence",
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
        Index(value = ["fused_face_id", "recognizer_id", "cluster_id"]),
    ]
)
data class IdentityInferenceEvidence(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")                val id: Long = 0,
    @ColumnInfo(name = "asset_id")          val assetId: String,
    @ColumnInfo(name = "fused_face_id")     val fusedFaceId: String,
    @ColumnInfo(name = "cluster_id")        val clusterId: String,
    @ColumnInfo(name = "tag_key")           val tagKey: String,
    @ColumnInfo(name = "tag_name")          val tagName: String,
    @ColumnInfo(name = "tag_id")            val tagId: Long,
    @ColumnInfo(name = "recognizer_id")     val recognizerId: String,
    @ColumnInfo(name = "recognizer_version") val recognizerVersion: String,
    @ColumnInfo(name = "similarity_score")  val similarityScore: Float,
    @ColumnInfo(name = "pipeline_run_id")   val pipelineRunId: String,
    @ColumnInfo(name = "inferred_at")       val inferredAt: Long = System.currentTimeMillis(),
)
