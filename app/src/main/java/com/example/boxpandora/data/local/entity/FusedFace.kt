package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A face detection result produced by the ensemble face pipeline.
 *
 * Each row represents one face in a media asset; the bounding box and confidence are the
 * weighted-average output of [FaceDetectionFusionEngine] merging detections from one or more
 * enabled face detectors.
 *
 * In [AiPipelineMode.SINGLE_ACTIVE] mode these rows are NOT written; only [DetectedFace] is used.
 *
 * @param fusedFaceId               Stable identifier: "${assetId}_fused_${faceIndex}".
 * @param assetId                   URI of the source media item (FK → media_index).
 * @param faceIndex                 Sequential index within the asset (0-based).
 * @param leftNorm                  Weighted-average left edge, normalized [0, 1].
 * @param topNorm                   Weighted-average top edge.
 * @param rightNorm                 Weighted-average right edge.
 * @param bottomNorm                Weighted-average bottom edge.
 * @param fusedConfidence           Average confidence across contributing detectors.
 * @param strongestConfidence       Highest individual detector confidence in this group.
 * @param contributingDetectorIds   Comma-separated IDs of contributing detectors.
 * @param anchorDetectorId          Highest-confidence detector ID (used for face alignment).
 * @param anchorDetectorVersion     Room version key of [anchorDetectorId].
 * @param pipelineRunId             UUID identifying the ensemble run that produced this row.
 * @param createdAt                 Epoch millis when this row was written.
 */
@Entity(
    tableName = "fused_faces",
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
        Index(value = ["asset_id", "pipeline_run_id"]),
    ]
)
data class FusedFace(
    @PrimaryKey
    @ColumnInfo(name = "fused_face_id")         val fusedFaceId: String,
    @ColumnInfo(name = "asset_id")              val assetId: String,
    @ColumnInfo(name = "face_index")            val faceIndex: Int,
    @ColumnInfo(name = "left_norm")             val leftNorm: Double,
    @ColumnInfo(name = "top_norm")              val topNorm: Double,
    @ColumnInfo(name = "right_norm")            val rightNorm: Double,
    @ColumnInfo(name = "bottom_norm")           val bottomNorm: Double,
    @ColumnInfo(name = "fused_confidence")      val fusedConfidence: Double,
    @ColumnInfo(name = "strongest_confidence")  val strongestConfidence: Double,
    /** CSV of contributing detector IDs. */
    @ColumnInfo(name = "contributing_detector_ids") val contributingDetectorIds: String,
    @ColumnInfo(name = "anchor_detector_id")    val anchorDetectorId: String,
    @ColumnInfo(name = "anchor_detector_version") val anchorDetectorVersion: String,
    @ColumnInfo(name = "pipeline_run_id")       val pipelineRunId: String,
    @ColumnInfo(name = "created_at")            val createdAt: Long = System.currentTimeMillis(),
)
