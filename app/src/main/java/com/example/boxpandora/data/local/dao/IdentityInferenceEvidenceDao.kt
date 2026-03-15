package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.IdentityInferenceEvidence

@Dao
interface IdentityInferenceEvidenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(evidence: List<IdentityInferenceEvidence>)

    @Query("SELECT * FROM identity_inference_evidence WHERE fused_face_id = :fusedFaceId")
    suspend fun getForFace(fusedFaceId: String): List<IdentityInferenceEvidence>

    @Query("SELECT * FROM identity_inference_evidence WHERE asset_id = :assetId")
    suspend fun getForAsset(assetId: String): List<IdentityInferenceEvidence>

    @Query("DELETE FROM identity_inference_evidence WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM identity_inference_evidence WHERE recognizer_version = :recognizerVersion")
    suspend fun deleteForRecognizerVersion(recognizerVersion: String)

    /** All identity evidence — used by fused-identity rebuild to re-fuse from stored evidence. */
    @Query("SELECT * FROM identity_inference_evidence")
    suspend fun getAll(): List<IdentityInferenceEvidence>

    @Query("DELETE FROM identity_inference_evidence")
    suspend fun deleteAll()
}
