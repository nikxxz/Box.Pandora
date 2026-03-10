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
    @ColumnInfo(name = "left_norm") val leftNorm: Double = 0.0,
    @ColumnInfo(name = "top_norm") val topNorm: Double = 0.0,
    @ColumnInfo(name = "right_norm") val rightNorm: Double = 0.0,
    @ColumnInfo(name = "bottom_norm") val bottomNorm: Double = 0.0,
    @ColumnInfo(name = "width_px") val widthPx: Int = 0,
    @ColumnInfo(name = "height_px") val heightPx: Int = 0,
    @ColumnInfo(name = "yaw") val yaw: Double = 0.0,
    @ColumnInfo(name = "pitch") val pitch: Double = 0.0,
    @ColumnInfo(name = "roll") val roll: Double = 0.0,
    @ColumnInfo(name = "quality_score") val qualityScore: Double = 0.0,
    @ColumnInfo(name = "cluster_id") val clusterId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
