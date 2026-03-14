package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Tracks the outcome of a face-scan attempt for a specific asset + detector version pair.
 *
 * Every processed asset gets exactly one row (REPLACE on conflict). [resultStatus] records
 * what happened so the pipeline can make smart retry decisions:
 *
 *  - [RESULT_FACES_FOUND]    – detection ran; at least one face was stored. Do not re-run.
 *  - [RESULT_NO_FACES_FOUND] – detection ran; zero faces. Do not re-run (the image has no faces
 *                               for this detector; re-running wastes battery).
 *  - [RESULT_FAILED]         – an exception prevented the image from being processed (I/O error,
 *                               OOM, corrupt bitmap, etc.). The item is left eligible for retry
 *                               so it will be picked up on the next worker run.
 *  - [RESULT_SKIPPED]        – the item was intentionally skipped in the current run (e.g.
 *                               GIF/video with the feature flag off). Reserved for future use;
 *                               not currently written.
 *
 * [FaceDao.getUnprocessedImageUris] and its count companion exclude only
 * [RESULT_FACES_FOUND] and [RESULT_NO_FACES_FOUND] rows, so [RESULT_FAILED] items are always
 * retried until they succeed.
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
    @ColumnInfo(name = "asset_id")       val assetId: String,
    @ColumnInfo(name = "detector_version") val detectorVersion: String,
    @ColumnInfo(name = "scanned_at")     val scannedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "result_status")  val resultStatus: String = RESULT_NO_FACES_FOUND
) {
    companion object {
        const val RESULT_FACES_FOUND    = "faces_found"
        const val RESULT_NO_FACES_FOUND = "no_faces_found"
        const val RESULT_FAILED         = "failed"
        const val RESULT_SKIPPED        = "skipped"
    }
}
