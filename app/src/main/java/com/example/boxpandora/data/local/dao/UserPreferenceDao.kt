package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.UserPreference
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPreferenceDao {
    @Query("SELECT * FROM user_preferences")
    fun getAllPreferencesFlow(): Flow<List<UserPreference>>

    @Query("SELECT * FROM user_preferences WHERE `key` = :key")
    suspend fun getByKey(key: String): UserPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preference: UserPreference)

    @Query("DELETE FROM user_preferences WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}
