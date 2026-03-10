package com.example.boxpandora.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_log")
data class ScanLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "scan_type") val scanType: String, // 'full' | 'incremental'
    @ColumnInfo(name = "album_scope") val albumScope: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "files_added") val filesAdded: Int = 0,
    @ColumnInfo(name = "files_removed") val filesRemoved: Int = 0,
    @ColumnInfo(name = "files_updated") val filesUpdated: Int = 0,
    @ColumnInfo(name = "status") val status: String = "running",
    @ColumnInfo(name = "error") val error: String? = null
)
