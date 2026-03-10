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
    @ColumnInfo(name = "dim") val dim: Int = 128,
    @ColumnInfo(name = "n") val n: Int = 0,
    @ColumnInfo(name = "tag_id") val tagId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
