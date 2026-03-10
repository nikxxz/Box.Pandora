package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tag_change_history",
    foreignKeys = [
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagReviewQueue::class,
            parentColumns = ["id"],
            childColumns = ["review_queue_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("tag_id"),
        Index("changed_at")
    ]
)
data class TagChangeHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "tag_name") val tagName: String,
    @ColumnInfo(name = "field_changed") val fieldChanged: String, // 'category', 'description', 'alias_added'
    @ColumnInfo(name = "old_value") val oldValue: String?,
    @ColumnInfo(name = "new_value") val newValue: String,
    @ColumnInfo(name = "change_source") val changeSource: String = "ai_review", // 'ai_review' | 'user'
    @ColumnInfo(name = "review_queue_id") val reviewQueueId: Long?,
    @ColumnInfo(name = "changed_at") val changedAt: Long = System.currentTimeMillis()
)
