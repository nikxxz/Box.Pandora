package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Tracks which assets have been scanned by a specific detector version, regardless of how many
 * faces were found.
 *
 * This closes the Phase 4 limitation: images where zero faces are detected have no rows in
 * [DetectedFace], so [FaceDao.getUnprocessedImageUris] kept returning them on every worker run.
 * A row here signals "this asset was fully scanned by [detectorVersion]" even if zero faces
 * were detected.
 *
 * Written by [FaceIndexWorker] after each image is processed (success or zero-face result).
 * Not written for images that failed with an exception — they will be retried next run.
 */
@Entity(
    tableName = "face_scan_log",
    primaryKeys = ["asset_id", "detector_version"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItem::class,
            parentColumns = ["uri"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FaceScanLog(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "detector_version") val detectorVersion: String,
    @ColumnInfo(name = "scanned_at") val scannedAt: Long = System.currentTimeMillis()
)
