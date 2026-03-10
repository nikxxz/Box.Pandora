package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_preferences")
data class UserPreference(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value") val value: String, // JSON-encoded
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
