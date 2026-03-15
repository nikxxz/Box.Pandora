package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.ModelInferenceEvidence

@Dao
interface ModelInferenceEvidenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(evidence: List<ModelInferenceEvidence>)

    /** All raw evidence for a single asset, across all models and pipeline runs. */
    @Query("SELECT * FROM model_inference_evidence WHERE asset_id = :assetId ORDER BY inferred_at DESC")
    suspend fun getForAsset(assetId: String): List<ModelInferenceEvidence>

    /** Raw evidence for a specific asset and pipeline run — used by the fusion engine. */
    @Query("SELECT * FROM model_inference_evidence WHERE asset_id = :assetId AND pipeline_run_id = :runId")
    suspend fun getForAssetAndRun(assetId: String, runId: String): List<ModelInferenceEvidence>

    /** Raw evidence for a specific asset and model version — used to check if inference is cached. */
    @Query("SELECT * FROM model_inference_evidence WHERE asset_id = :assetId AND model_version = :modelVersion")
    suspend fun getForAssetAndModel(assetId: String, modelVersion: String): List<ModelInferenceEvidence>

    /** Delete all evidence for an asset — called before a fresh ensemble run for that asset. */
    @Query("DELETE FROM model_inference_evidence WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    /** Delete all evidence produced by a specific model version. */
    @Query("DELETE FROM model_inference_evidence WHERE model_version = :modelVersion")
    suspend fun deleteForModelVersion(modelVersion: String)

    /** All distinct asset IDs that have evidence rows — used by fused-tag rebuild. */
    @Query("SELECT DISTINCT asset_id FROM model_inference_evidence")
    suspend fun getDistinctAssetIds(): List<String>

    /** Delete all evidence — used by "Clear All AI Data" and full-rescan actions. */
    @Query("DELETE FROM model_inference_evidence")
    suspend fun deleteAll()
}
