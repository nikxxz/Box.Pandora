package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "detected_faces",
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
        Index("cluster_id")
    ]
)
data class DetectedFace(
    @PrimaryKey @ColumnInfo(name = "face_id") val faceId: String, // "{asset_id}_{face_index}"
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "face_index") val faceIndex: Int,
    /**
     * Version key of the face detector that produced this row (from ModelMetadata.roomVersionKey).
     * Added in schema v7. Empty string means "unknown / pre-v7". Used by FaceIndexWorker to skip
     * assets already processed with the current detector, and to identify stale rows after a
     * detector model upgrade.
     *
     * NOTE: Zero-face images produce no rows, so they cannot be distinguished from
     * never-processed images using this column alone. A dedicated face_scan_log table
     * (planned for Phase 5) will close this gap.
     */
    @ColumnInfo(name = "detector_model_version") val detectorModelVersion: String = "",
    @ColumnInfo(name = "left_norm") val leftNorm: Double = 0.0,
    @ColumnInfo(name = "top_norm") val topNorm: Double = 0.0,
    @ColumnInfo(name = "right_norm") val rightNorm: Double = 0.0,
    @ColumnInfo(name = "bottom_norm") val bottomNorm: Double = 0.0,
    @ColumnInfo(name = "width_px") val widthPx: Int = 0,
    @ColumnInfo(name = "height_px") val heightPx: Int = 0,
    /** Pose angles. Phase 4 sets these to 0.0; landmark-derived pose estimation is Phase 5. */
    @ColumnInfo(name = "yaw") val yaw: Double = 0.0,
    @ColumnInfo(name = "pitch") val pitch: Double = 0.0,
    @ColumnInfo(name = "roll") val roll: Double = 0.0,
    /** Confidence score from the detector (0.0–1.0). Used as a quality proxy in Phase 4. */
    @ColumnInfo(name = "quality_score") val qualityScore: Double = 0.0,
    @ColumnInfo(name = "cluster_id") val clusterId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
