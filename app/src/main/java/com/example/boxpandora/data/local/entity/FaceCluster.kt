package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "face_clusters",
    foreignKeys = [
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("tag_id")
    ]
)
data class FaceCluster(
    @PrimaryKey @ColumnInfo(name = "cluster_id") val clusterId: String, // UUID
    @ColumnInfo(name = "centroid_blob") val centroidBlob: ByteArray?,
    @ColumnInfo(name = "dim") val dim: Int = 512, // ArcFace ResNet-100 output dimension
    @ColumnInfo(name = "n") val n: Int = 0,       // face count — rebuilt on each cluster run
    @ColumnInfo(name = "tag_id") val tagId: Long? = null,
    /** User-assigned label for this cluster. Null until the user names it. */
    @ColumnInfo(name = "name") val name: String? = null,
    /** True once the user has explicitly confirmed or named this cluster. Confirmed
     *  clusters drive PersonSuggestionWorker matches. */
    @ColumnInfo(name = "confirmed_by_user") val confirmedByUser: Boolean = false,
    /** Hidden clusters are excluded from the PeopleScreen grid but not deleted. */
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean = false,
    /**
     * Room version key of the face embedding model that produced the [centroidBlob].
     * Format: "face_embedding:<model-id>-<version>" — matches [ModelMetadata.roomVersionKey].
     *
     * Empty string for clusters created before schema v12 (pre-provenance rows).
     * [PersonSuggestionWorker] skips clusters where this does not match the active embedder
     * version to prevent stale centroids from generating misleading suggestions after a
     * model upgrade.
     */
    @ColumnInfo(name = "embedder_version") val embedderVersion: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
