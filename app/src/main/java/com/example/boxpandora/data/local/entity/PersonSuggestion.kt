package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A suggestion that an unassigned (or newly indexed) face might belong to a confirmed cluster.
 *
 * [PersonSuggestionWorker] computes these by comparing each unassigned face's embedding against
 * the centroid of every [FaceCluster] where [FaceCluster.confirmedByUser] = true.
 *
 * The user accepts or rejects each suggestion in the People/ClusterDetail UI:
 *  - 'pending'  : not yet reviewed
 *  - 'accepted' : user confirmed → face is assigned to cluster (a FaceClusterCorrection is written)
 *  - 'rejected' : user dismissed → face stays unassigned (an 'exclude' correction may be written)
 */
@Entity(
    tableName = "person_suggestions",
    foreignKeys = [
        ForeignKey(
            entity = DetectedFace::class,
            parentColumns = ["face_id"],
            childColumns = ["face_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FaceCluster::class,
            parentColumns = ["cluster_id"],
            childColumns = ["cluster_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("face_id"),
        Index("cluster_id"),
        Index(value = ["face_id", "cluster_id"], unique = true)
    ]
)
data class PersonSuggestion(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "face_id") val faceId: String,
    @ColumnInfo(name = "cluster_id") val clusterId: String,
    /** Cosine similarity between the face embedding and the cluster centroid (0–1). */
    @ColumnInfo(name = "similarity") val similarity: Float,
    /** 'pending', 'accepted', or 'rejected' */
    @ColumnInfo(name = "status") val status: String = STATUS_PENDING,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING  = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"
    }
}
