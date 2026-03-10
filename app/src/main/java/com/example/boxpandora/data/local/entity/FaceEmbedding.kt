package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "face_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = DetectedFace::class,
            parentColumns = ["face_id"],
            childColumns = ["face_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FaceEmbedding(
    @PrimaryKey @ColumnInfo(name = "face_id") val faceId: String,
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "dim") val dim: Int = 128,
    @ColumnInfo(name = "embedding") val embedding: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
