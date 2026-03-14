package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores reversible user corrections to the automatic face clustering.
 *
 * Each row records one user action:
 *  - action = 'assign'  : move [faceId] into [toClusterId]. [fromClusterId] records where it was.
 *  - action = 'exclude' : remove [faceId] from clustering entirely. [toClusterId] is null.
 *
 * [FaceClusterEngine] reads all corrections as hard constraints before re-assigning faces,
 * so corrections survive full cluster rebuilds.
 *
 * Corrections are scoped to one face. If the user merges two clusters or splits a cluster,
 * that operation is expressed as individual face-level corrections by [ClusterDetailViewModel].
 */
@Entity(
    tableName = "face_cluster_corrections",
    foreignKeys = [
        ForeignKey(
            entity = DetectedFace::class,
            parentColumns = ["face_id"],
            childColumns = ["face_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("face_id")]
)
data class FaceClusterCorrection(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "face_id") val faceId: String,
    /** 'assign' or 'exclude' */
    @ColumnInfo(name = "action") val action: String,
    /** Cluster the face is being assigned to. Null when action = 'exclude'. */
    @ColumnInfo(name = "to_cluster_id") val toClusterId: String? = null,
    /** Cluster the face was in before this correction (informational; used for undo). */
    @ColumnInfo(name = "from_cluster_id") val fromClusterId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ACTION_ASSIGN = "assign"
        const val ACTION_EXCLUDE = "exclude"
    }
}
