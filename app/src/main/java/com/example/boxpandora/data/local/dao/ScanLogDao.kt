package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.boxpandora.data.local.entity.ScanLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanLogDao {
    @Query("SELECT * FROM scan_log ORDER BY started_at DESC")
    fun getAllLogsFlow(): Flow<List<ScanLog>>

    @Query("SELECT * FROM scan_log ORDER BY started_at DESC LIMIT 1")
    suspend fun getLatestLog(): ScanLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ScanLog): Long

    @Update
    suspend fun update(log: ScanLog)

    @Query("DELETE FROM scan_log")
    suspend fun clearAll()
}
