package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.ModelReliabilityStats

@Dao
interface ModelReliabilityStatsDao {

    /** Insert or replace a reliability record (keyed on the unique constraint). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: ModelReliabilityStats)

    /**
     * Returns the record for a specific (modelId, pipelineCategory, tagCategory) key, or null.
     * Model version is not part of the lookup — reliability persists across version bumps.
     */
    @Query("""
        SELECT * FROM model_reliability_stats
        WHERE model_id         = :modelId
          AND pipeline_category = :pipelineCategory
          AND tag_category      = :tagCategory
        LIMIT 1
    """)
    suspend fun getRecord(
        modelId: String,
        pipelineCategory: String,
        tagCategory: String,
    ): ModelReliabilityStats?

    /** All records for a single model across every (pipeline, tag) combination. */
    @Query("SELECT * FROM model_reliability_stats WHERE model_id = :modelId")
    suspend fun getAllForModel(modelId: String): List<ModelReliabilityStats>

    /**
     * All scene-embedding reliability records.
     * Loaded once per worker run and converted to a weight lookup map by the orchestrator.
     */
    @Query("SELECT * FROM model_reliability_stats WHERE pipeline_category = 'scene_embedding'")
    suspend fun getAllSceneWeights(): List<ModelReliabilityStats>

    /**
     * All face-recognizer identity reliability records.
     * Loaded once per worker run and converted to a weight lookup by the orchestrator.
     */
    @Query("""
        SELECT * FROM model_reliability_stats
        WHERE pipeline_category = 'face_embedding'
          AND tag_category      = 'identity'
    """)
    suspend fun getAllIdentityWeights(): List<ModelReliabilityStats>

    /**
     * All records for a given (modelId, pipelineCategory) combination, across all tag categories.
     * Used by [ReliabilityUpdateService.applyModelPenalty] to apply health/staleness penalties
     * to every tag-category bucket for a model without touching accept/reject counts.
     */
    @Query("""
        SELECT * FROM model_reliability_stats
        WHERE model_id          = :modelId
          AND pipeline_category = :pipelineCategory
    """)
    suspend fun getRecordsForModelPipeline(
        modelId: String,
        pipelineCategory: String,
    ): List<ModelReliabilityStats>

    /** Deletes all reliability stats — called by "Clear All AI Data" maintenance action. */
    @Query("DELETE FROM model_reliability_stats")
    suspend fun deleteAll()
}
