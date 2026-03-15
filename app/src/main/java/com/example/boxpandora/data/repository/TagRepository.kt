package com.example.boxpandora.data.repository

import androidx.room.withTransaction
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.dao.*
import com.example.boxpandora.data.local.entity.*
import com.example.boxpandora.ml.ensemble.ReliabilityUpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

class TagRepository(
    val database: AppDatabase,
    private val tagDao: TagDao,
    private val mediaTagDao: MediaTagDao,
    private val tagAliasDao: TagAliasDao,
    private val tagSuggestionDao: TagSuggestionDao,
    private val heuristicTagDao: HeuristicTagDao,
    private val tagRejectionDao: TagRejectionDao,
    private val tagChangeHistoryDao: TagChangeHistoryDao,
    private val tagCooccurrenceDao: TagCooccurrenceDao
) {

    /**
     * Lazily created — only instantiated if ensemble suggestions exist to update.
     * Shares [database] to avoid a second DB handle.
     */
    private val reliabilityService by lazy { ReliabilityUpdateService(database) }

    fun getAllTagsFlow(): Flow<List<Tag>> = tagDao.getAllTagsFlow()

    fun getTagFlow(tagId: Long): Flow<Tag?> = tagDao.getByIdFlow(tagId)

    fun getRelatedTagsFlow(tagId: Long, limit: Int = 8): Flow<List<RelatedTag>> {
        // Single JOIN query — previously this did N+1 tagDao.getById() calls per cooccurrence.
        return tagCooccurrenceDao.getRelatedTagsWithCountFlow(tagId, limit).map { results ->
            results.map { r -> RelatedTag(r.tag, r.count) }
        }
    }

    suspend fun getCoverForTag(tagId: Long): String? = withContext(Dispatchers.IO) {
        mediaTagDao.getCoverMediaUri(tagId)
    }

    suspend fun getTagNamesForUris(uris: List<String>) = withContext(Dispatchers.IO) {
        mediaTagDao.getTagNamesForUris(uris)
    }

    fun getTagsByCategoryFlow(category: String): Flow<List<Tag>> = 
        tagDao.getTagsByCategoryFlow(category)

    fun getTagsForMedia(mediaUri: String): Flow<List<Tag>> = 
        mediaTagDao.getTagsForMedia(mediaUri)

    /**
     * Resolves a tag by name, considering normalization and aliases.
     */
    suspend fun resolveTagByName(name: String): Tag? = withContext(Dispatchers.IO) {
        val normalized = normalizeTagName(name)
        
        // 1. Exact normalized match
        tagDao.getByNormalizedName(normalized)?.let { return@withContext it }
        
        // 2. Alias match
        tagAliasDao.getByAlias(normalized)?.let { alias ->
            return@withContext tagDao.getById(alias.tagId)
        }
        
        null
    }

    /**
     * Creates a new canonical tag if it doesn't exist.
     * Uses the provided name for display but ensures a unique normalized key.
     */
    suspend fun getOrCreateTag(name: String, category: String = "misc"): Tag = withContext(Dispatchers.IO) {
        resolveTagByName(name) ?: run {
            val normalized = normalizeTagName(name)
            // We use a properly formatted display name (e.g., "Dog" instead of "dog ")
            val displayName = name.trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            
            val newTag = Tag(
                name = displayName,
                normalizedName = normalized,
                category = category
            )
            val id = tagDao.insert(newTag)
            newTag.copy(id = id)
        }
    }

    /**
     * Attaches a tag to a media item.
     */
    suspend fun attachTagToMedia(mediaUri: String, tagName: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val tag = getOrCreateTag(tagName)
            val existing = mediaTagDao.getMediaTagsForUri(mediaUri)
            if (existing.none { it.tagId == tag.id }) {
                mediaTagDao.insert(MediaTag(mediaUri, tag.id))
                tagDao.updateUsageCount(tag.id, 1)
                
                // Record co-occurrence with other tags on this media
                existing.forEach { other ->
                    tagCooccurrenceDao.recordCooccurrence(tag.id, other.tagId)
                }
            }
        }
    }

    /**
     * Detaches a tag from a media item.
     */
    suspend fun detachTagFromMedia(mediaUri: String, tagId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existing = mediaTagDao.getMediaTagsForUri(mediaUri)
            if (existing.any { it.tagId == tagId }) {
                mediaTagDao.delete(mediaUri, tagId)
                tagDao.updateUsageCount(tagId, -1)
            }
        }
    }

    /**
     * Bulk attach tags to many media items.
     */
    suspend fun bulkAttachTags(mediaUris: List<String>, tagNames: List<String>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val tags = tagNames.map { getOrCreateTag(it) }
            for (uri in mediaUris) {
                val existingTagIds = mediaTagDao.getMediaTagsForUri(uri).map { it.tagId }.toSet()
                val toAdd = tags.filter { !existingTagIds.contains(it.id) }
                
                if (toAdd.isNotEmpty()) {
                    mediaTagDao.insertAll(toAdd.map { MediaTag(uri, it.id) })
                    toAdd.forEach { tagDao.updateUsageCount(it.id, 1) }
                    
                    // Update co-occurrences
                    val allCurrentIds = existingTagIds + toAdd.map { it.id }
                    toAdd.forEach { newTag ->
                        allCurrentIds.forEach { otherId ->
                            if (newTag.id != otherId) {
                                tagCooccurrenceDao.recordCooccurrence(newTag.id, otherId)
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun removeTagFromMediaBulk(mediaUris: List<String>, tagId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            mediaUris.forEach { uri ->
                val existing = mediaTagDao.getMediaTagsForUri(uri)
                if (existing.any { it.tagId == tagId }) {
                    mediaTagDao.delete(uri, tagId)
                    tagDao.updateUsageCount(tagId, -1)
                }
            }
        }
    }

    /**
     * Renames a canonical tag safely.
     * If keepOldAsAlias is true, the old normalized name is added to the alias table.
     */
    suspend fun renameTag(tagId: Long, newName: String, keepOldAsAlias: Boolean = true) = withContext(Dispatchers.IO) {
        val oldTag = tagDao.getById(tagId) ?: return@withContext
        val normalized = normalizeTagName(newName)
        val existing = resolveTagByName(normalized)
        
        database.withTransaction {
            if (existing != null) {
                if (existing.id != tagId) {
                    mergeTags(sourceTagId = tagId, targetTagId = existing.id)
                }
            } else {
                if (keepOldAsAlias) {
                    tagAliasDao.insert(
                        TagAlias(
                            tagId = tagId,
                            alias = oldTag.normalizedName,
                            source = "user"
                        )
                    )
                }
                tagDao.updateName(tagId, newName.trim(), normalized)
                
                tagChangeHistoryDao.insert(
                    TagChangeHistory(
                        tagId = tagId,
                        tagName = newName.trim(),
                        fieldChanged = "name",
                        oldValue = oldTag.name,
                        newValue = newName.trim(),
                        changeSource = "user",
                        reviewQueueId = null
                    )
                )
            }
        }
    }

    /**
     * Merges source tag into target tag and deletes source.
     */
    suspend fun mergeTags(sourceTagId: Long, targetTagId: Long) = withContext(Dispatchers.IO) {
        if (sourceTagId == targetTagId) return@withContext
        
        val sourceTag = tagDao.getById(sourceTagId) ?: return@withContext
        val targetTag = tagDao.getById(targetTagId) ?: return@withContext
        
        database.withTransaction {
            // 1. Move all tag relations to target tag, ignoring duplicates
            mediaTagDao.transferTags(sourceTagId, targetTagId)
            
            // 2. Delete any leftover relations for source tag (the ones that were duplicates)
            mediaTagDao.deleteByTagId(sourceTagId)
            
            // 3. Recalculate target tag usage count
            val newCount = mediaTagDao.getUsageCount(targetTagId)
            tagDao.update(targetTag.copy(usageCount = newCount))
            
            // 4. Move aliases from source to target
            val sourceAliases = tagAliasDao.getByTagId(sourceTagId)
            sourceAliases.forEach { alias ->
                tagAliasDao.insert(alias.copy(id = 0, tagId = targetTagId))
            }
            // Add the source tag's own name as an alias to the target tag
            tagAliasDao.insert(TagAlias(tagId = targetTagId, alias = sourceTag.normalizedName, source = "user"))
            tagAliasDao.deleteByTagId(sourceTagId)
            
            // 5. Log history
            tagChangeHistoryDao.insert(
                TagChangeHistory(
                    tagId = targetTagId,
                    tagName = targetTag.name,
                    fieldChanged = "merged_from",
                    oldValue = sourceTag.name,
                    newValue = targetTag.name,
                    changeSource = "user",
                    reviewQueueId = null
                )
            )
            
            // 6. Delete source tag
            tagDao.deleteById(sourceTagId)
        }
    }

    /**
     * Deletes a tag and all its associations.
     */
    suspend fun deleteTag(tagId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            mediaTagDao.deleteByTagId(tagId)
            tagAliasDao.deleteByTagId(tagId)
            tagDao.deleteById(tagId)
        }
    }

    suspend fun updateDescription(tagId: Long, description: String?) = withContext(Dispatchers.IO) {
        tagDao.updateDescription(tagId, description)
    }

    suspend fun updateCategory(tagId: Long, category: String) = withContext(Dispatchers.IO) {
        tagDao.updateCategory(tagId, category)
    }

    suspend fun addAlias(tagId: Long, alias: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeTagName(alias)
        if (resolveTagByName(normalized) == null) {
            tagAliasDao.insert(TagAlias(tagId = tagId, alias = normalized, source = "user"))
        }
    }

    /**
     * Fetches suggestion candidates for a given media item, filtered by rejections.
     */
    suspend fun getSuggestionsForMedia(mediaUri: String): List<String> = withContext(Dispatchers.IO) {
        val suggestions = tagSuggestionDao.getForAsset(mediaUri).map { it.tagKey }
        val heuristics = heuristicTagDao.getForAsset(mediaUri).map { it.tagKey }
        val rejections = tagRejectionDao.getForAsset(mediaUri).map { it.tagKey }.toSet()
        val tagIds = mediaTagDao.getMediaTagsForUri(mediaUri).map { it.tagId }
        val currentTags = if (tagIds.isEmpty()) emptySet()
            else tagDao.getByIds(tagIds).map { it.normalizedName }.toSet()

        (suggestions + heuristics)
            .distinct()
            .filter { it !in rejections && it !in currentTags }
    }

    /**
     * Fetches per-asset suggestion candidates as [RichSuggestion], filtered by rejections
     * and already-applied tags.
     *
     * Merges three source pipelines (highest-score wins for duplicate tag keys):
     *  - [tagSuggestionDao]        — single-model scene suggestions
     *  - [heuristicTagDao]         — rule-based heuristic suggestions
     *  - [fusedSceneSuggestionDao] — ensemble-fused scene suggestions (status=pending only)
     *
     * Fused rows carry structured provenance ([RichSuggestion.contributingModelCount],
     * [RichSuggestion.agreementLevel], [RichSuggestion.isAmbiguous]) so the UI can display
     * an explainability badge without parsing source strings.
     *
     * Returns at most 8 results sorted by descending score.
     */
    suspend fun getSuggestionObjectsForMedia(mediaUri: String): List<RichSuggestion> = withContext(Dispatchers.IO) {
        val richSingle = tagSuggestionDao.getForAsset(mediaUri).map { s ->
            RichSuggestion(
                id           = s.id,
                assetId      = s.assetId,
                tagKey       = s.tagKey,
                score        = s.score,
                source       = s.source,
                modelVersion = s.modelVersion,
            )
        }
        val richHeuristics = heuristicTagDao.getForAsset(mediaUri).map { h ->
            RichSuggestion(
                id      = 0L,
                assetId = h.assetId,
                tagKey  = h.tagKey,
                score   = h.score,
                source  = "heuristic",
            )
        }
        val richFused = database.fusedSceneSuggestionDao().getPendingForAsset(mediaUri).map { f ->
            RichSuggestion(
                id                     = f.id,
                assetId                = f.assetId,
                tagKey                 = f.canonicalTagKey,
                score                  = f.fusedScore.toDouble(),
                source                 = "fused_scene",
                modelVersion           = f.pipelineRunId,
                contributingModelCount = f.contributingModelCount,
                agreementLevel         = f.agreementLevel,
                isAmbiguous            = f.isAmbiguous,
                tagCategory            = f.tagCategory,
                isFused                = true,
            )
        }
        val rejections = tagRejectionDao.getForAsset(mediaUri).map { it.tagKey }.toSet()
        val tagIds = mediaTagDao.getMediaTagsForUri(mediaUri).map { it.tagId }
        val currentTags = if (tagIds.isEmpty()) emptySet()
            else tagDao.getByIds(tagIds).map { it.normalizedName }.toSet()

        (richSingle + richHeuristics + richFused)
            .filter { it.tagKey !in rejections && it.tagKey !in currentTags }
            .groupBy { it.tagKey }
            .map { (_, dupes) -> dupes.maxByOrNull { it.score }!! }
            .sortedByDescending { it.score }
            .take(8)
    }

    /**
     * Returns a merged list of pending suggestions from all active pipelines, suitable
     * for populating the global suggestions review screen.
     *
     * Combines single-model [TagSuggestion] rows filtered to [modelVersion] with
     * ensemble-fused [FusedSceneSuggestion] rows. Rejection and already-applied-tag
     * filtering is handled at the DAO layer for each source. When the same (asset, tagKey)
     * pair appears in both pipelines the entry with the higher score is kept.
     *
     * Returns at most [limit] results sorted by descending score.
     */
    suspend fun getGlobalPendingRichSuggestions(
        minScore: Double,
        modelVersion: String,
        limit: Int = 300,
    ): List<RichSuggestion> = withContext(Dispatchers.IO) {
        val single = tagSuggestionDao.getPendingSuggestions(minScore, modelVersion, limit)
            .map { s ->
                RichSuggestion(
                    id           = s.id,
                    assetId      = s.assetId,
                    tagKey       = s.tagKey,
                    score        = s.score,
                    source       = s.source,
                    modelVersion = s.modelVersion,
                )
            }
        val fused = database.fusedSceneSuggestionDao().getPendingSuggestions(minScore, limit)
            .map { f ->
                RichSuggestion(
                    id                     = f.id,
                    assetId                = f.assetId,
                    tagKey                 = f.canonicalTagKey,
                    score                  = f.fusedScore.toDouble(),
                    source                 = "fused_scene",
                    modelVersion           = f.pipelineRunId,
                    contributingModelCount = f.contributingModelCount,
                    agreementLevel         = f.agreementLevel,
                    isAmbiguous            = f.isAmbiguous,
                    tagCategory            = f.tagCategory,
                    isFused                = true,
                )
            }
        (single + fused)
            .groupBy { "${it.assetId}:${it.tagKey}" }
            .map { (_, dupes) -> dupes.maxByOrNull { it.score }!! }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Accepts a suggestion and turns it into a canonical tag attachment.
     *
     * If a fused scene or identity suggestion exists for this (assetId, tagKey) pair, its
     * status is updated and [ReliabilityUpdateService] is notified so contributing models
     * receive credit for the correct prediction.
     */
    suspend fun acceptSuggestion(mediaUri: String, tagKey: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            attachTagToMedia(mediaUri, tagKey)
        }

        // ── Fused scene suggestion feedback ──────────────────────────────────
        database.fusedSceneSuggestionDao().getByAssetAndTagKey(mediaUri, tagKey)?.let { suggestion ->
            database.fusedSceneSuggestionDao().updateStatus(suggestion.id, "accepted")
            reliabilityService.onSceneSuggestionAccepted(suggestion)
        }

        // ── Fused identity suggestion feedback ───────────────────────────────
        // A tagKey may match multiple faces on the same asset — update all.
        database.fusedIdentitySuggestionDao().getByAssetAndTagKey(mediaUri, tagKey)
            .forEach { suggestion ->
                database.fusedIdentitySuggestionDao()
                    .updateStatus(suggestion.id, FusedIdentitySuggestion.STATUS_ACCEPTED)
                reliabilityService.onIdentitySuggestionAccepted(suggestion)
            }
    }

    /**
     * Rejects a suggestion and stores it in the rejection table to prevent it from reappearing.
     *
     * If a fused scene or identity suggestion exists for this (assetId, tagKey) pair, its
     * status is updated and [ReliabilityUpdateService] is notified so contributing models
     * receive a penalty signal.
     */
    suspend fun rejectSuggestion(mediaUri: String, tagKey: String) = withContext(Dispatchers.IO) {
        tagRejectionDao.insertAll(listOf(TagRejection(tagKey = tagKey, assetId = mediaUri)))

        // ── Fused scene suggestion feedback ──────────────────────────────────
        database.fusedSceneSuggestionDao().getByAssetAndTagKey(mediaUri, tagKey)?.let { suggestion ->
            database.fusedSceneSuggestionDao().updateStatus(suggestion.id, "rejected")
            reliabilityService.onSceneSuggestionRejected(suggestion)
        }

        // ── Fused identity suggestion feedback ───────────────────────────────
        database.fusedIdentitySuggestionDao().getByAssetAndTagKey(mediaUri, tagKey)
            .forEach { suggestion ->
                database.fusedIdentitySuggestionDao()
                    .updateStatus(suggestion.id, FusedIdentitySuggestion.STATUS_REJECTED)
                reliabilityService.onIdentitySuggestionRejected(suggestion)
            }
    }

    /**
     * Transfers all tag-related metadata from one media URI to another.
     * Used when a file is renamed or moved.
     */
    suspend fun transferTagMetadata(oldUri: String, newUri: String) = withContext(Dispatchers.IO) {
        if (oldUri == newUri) return@withContext

        database.withTransaction {
            // 1. Tags
            val tags = mediaTagDao.getMediaTagsForUri(oldUri)
            if (tags.isNotEmpty()) {
                mediaTagDao.insertAll(tags.map { it.copy(mediaUri = newUri) })
            }

            // 2. Suggestions
            val suggestions = tagSuggestionDao.getForAsset(oldUri)
            if (suggestions.isNotEmpty()) {
                tagSuggestionDao.insertAll(suggestions.map { it.copy(id = 0, assetId = newUri) })
            }

            // 3. Heuristics
            val heuristicTags = heuristicTagDao.getForAsset(oldUri)
            if (heuristicTags.isNotEmpty()) {
                heuristicTagDao.insertAll(heuristicTags.map { it.copy(assetId = newUri) })
            }

            // 4. Rejections
            val rejections = tagRejectionDao.getForAsset(oldUri)
            if (rejections.isNotEmpty()) {
                tagRejectionDao.insertAll(rejections.map { it.copy(assetId = newUri) })
            }
        }
    }

    /**
     * Normalizes a tag name for lookup keys.
     * Trims, lowercases, collapses spaces, and strips punctuation.
     */
    fun normalizeTagName(name: String): String {
        return name.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "") // Strip punctuation
            .replace(Regex("\\s+"), " ")              // Collapse duplicate spaces
    }
}

data class RelatedTag(val tag: Tag, val cooccurrenceCount: Int)
