package com.example.boxpandora.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.boxpandora.data.local.entity.FusedSceneSuggestion

@Dao
interface FusedSceneSuggestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(suggestions: List<FusedSceneSuggestion>)

    /** All fused suggestions for a single asset (UI source in ensemble mode). */
    @Query("SELECT * FROM fused_scene_suggestions WHERE asset_id = :assetId ORDER BY fused_score DESC")
    suspend fun getForAsset(assetId: String): List<FusedSceneSuggestion>

    /** Delete all fused suggestions for an asset — called before writing a fresh fusion result. */
    @Query("DELETE FROM fused_scene_suggestions WHERE asset_id = :assetId")
    suspend fun deleteForAsset(assetId: String)

    /**
     * All pending fused suggestions above [minScore] that haven't been rejected or already applied,
     * across all assets. Used by the suggestions screen when ensemble mode is active.
     */
    @Query("""
        SELECT fss.* FROM fused_scene_suggestions fss
        WHERE fss.fused_score >= :minScore
        AND fss.status = 'pending'
        AND NOT EXISTS (
            SELECT 1 FROM tag_rejections tr
            WHERE tr.asset_id = fss.asset_id AND tr.tag_key = fss.canonical_tag_key
        )
        AND NOT EXISTS (
            SELECT 1 FROM media_tags mt
            INNER JOIN tags t ON mt.tag_id = t.id
            WHERE mt.media_uri = fss.asset_id AND t.normalized_name = fss.canonical_tag_key
        )
        ORDER BY fss.fused_score DESC
        LIMIT :limit
    """)
    suspend fun getPendingSuggestions(
        minScore: Double,
        limit: Int = 300
    ): List<FusedSceneSuggestion>

    /** Pending fused suggestions for a single asset, used by the info-panel chip row. */
    @Query("""
        SELECT * FROM fused_scene_suggestions
        WHERE asset_id = :assetId AND status = 'pending'
        ORDER BY fused_score DESC
    """)
    suspend fun getPendingForAsset(assetId: String): List<FusedSceneSuggestion>

    /** Looks up the fused suggestion for a specific (asset, tag) pair, or null if absent. */
    @Query("SELECT * FROM fused_scene_suggestions WHERE asset_id = :assetId AND canonical_tag_key = :tagKey LIMIT 1")
    suspend fun getByAssetAndTagKey(assetId: String, tagKey: String): FusedSceneSuggestion?

    /** Updates the status of a single suggestion (e.g. "pending" → "accepted" / "rejected"). */
    @Query("UPDATE fused_scene_suggestions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /** Delete all fused suggestions. Used by "Clear All AI Data" and rebuild actions. */
    @Query("DELETE FROM fused_scene_suggestions")
    suspend fun deleteAll()
}
