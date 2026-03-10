package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tag_review_queue",
    foreignKeys = [
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("status"),
        Index("tag_id")
    ]
)
data class TagReviewQueue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "analysis_type") val analysisType: String, // 'category' | 'description' | 'alias' | 'canonical_name'
    @ColumnInfo(name = "suggested_value") val suggestedValue: String,
    @ColumnInfo(name = "reasoning") val reasoning: String?,
    @ColumnInfo(name = "status") val status: String = "pending", // 'pending' | 'accepted' | 'edited' | 'rejected'
    @ColumnInfo(name = "edited_value") val editedValue: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long? = null
)
