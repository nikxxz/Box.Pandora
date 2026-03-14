package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "image_embeddings",
    primaryKeys = ["asset_id", "model_version"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItem::class,
            parentColumns = ["uri"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("model_version")
    ]
)
data class ImageEmbedding(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "dim") val dim: Int,
    @ColumnInfo(name = "embedding") val embedding: ByteArray,
    /** "image", "gif", or "video" — matches [SceneEmbeddingService.MEDIA_TYPE_*] constants. */
    @ColumnInfo(name = "media_type") val mediaType: String = "image",
    /** Frame-sampling descriptor used to produce this embedding; null for still images. */
    @ColumnInfo(name = "scan_method") val scanMethod: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
