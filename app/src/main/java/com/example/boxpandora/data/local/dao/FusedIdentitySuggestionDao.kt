package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.FusedIdentitySuggestion

@Dao
interface FusedIdentitySuggestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(suggestions: List<FusedIdentitySuggestion>)

    @Query("SELECT * FROM fused_identity_suggestions WHERE fused_face_id = :fusedFaceId ORDER BY fused_score DESC")
    suspend fun getForFace(fusedFaceId: String): List<FusedIdentitySuggestion>

    @Query("SELECT * FROM fused_identity_suggestions WHERE asset_id = :assetId AND status = 'pending' ORDER BY fused_score DESC")
    suspend fun getPendingForAsset(assetId: String): List<FusedIdentitySuggestion>

    /**
     * Returns non-ambiguous pending suggestions above [minScore], ordered by fused score.
     * Excludes ambiguous results so only high-confidence suggestions are surfaced by default.
     */
    @Query("""
        SELECT * FROM fused_identity_suggestions
        WHERE status = 'pending'
        AND fused_score >= :minScore
        AND is_ambiguous = 0
        ORDER BY fused_score DESC
        LIMIT :limit
    """)
    suspend fun getUnambiguousPending(minScore: Float, limit: Int): List<FusedIdentitySuggestion>

    /**
     * All fused identity suggestions for a specific (asset, tagKey) pair.
     * May return multiple rows if the same person appears in more than one face on the asset.
     */
    @Query("SELECT * FROM fused_identity_suggestions WHERE asset_id = :assetId AND tag_key = :tagKey")
    suspend fun getByAssetAndTagKey(assetId: String, tagKey: String): List<FusedIdentitySuggestion>

    @Query("UPDATE fused_identity_suggestions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM fused_identity_suggestions WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    @Query("DELETE FROM fused_identity_suggestions WHERE fused_face_id = :fusedFaceId")
    suspend fun deleteForFace(fusedFaceId: String)

    @Query("DELETE FROM fused_identity_suggestions")
    suspend fun deleteAll()
}
