package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.TagRepository
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.search.SimilaritySearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A media item paired with its similarity score relative to the query image. */
data class SimilarMediaItem(
    val item: MediaItem,
    val similarity: Float,
    val rank: Int
)

/** Similarity band for display */
fun similarityBand(score: Float): String? = when {
    score >= 0.92f -> "Very Close"
    score >= 0.85f -> "Close"
    score >= 0.75f -> "Related"
    else           -> null // "Weak" — hidden by default
}

enum class SimilarSortOrder { CLOSEST_FIRST, NEWEST_FIRST, SAME_FOLDER }
enum class SimilarMediaFilter { IMAGES_ONLY, INCLUDE_GIFS, INCLUDE_VIDEOS }

data class SimilarImagesState(
    val isLoading: Boolean = true,
    val hasEmbedding: Boolean = false,
    /** Source item (the query image) */
    val sourceItem: MediaItem? = null,
    /** All results with scores, in current sort order, filtered by media type */
    val results: List<SimilarMediaItem> = emptyList(),
    val sortOrder: SimilarSortOrder = SimilarSortOrder.CLOSEST_FIRST,
    val mediaFilter: SimilarMediaFilter = SimilarMediaFilter.IMAGES_ONLY,
    /** URIs of currently selected items for batch tagging */
    val selectedUris: Set<String> = emptySet(),
    /** Pending batch tag confirmation: non-null when user taps "Apply tags" */
    val pendingBatchTags: List<Tag>? = null
) {
    // Legacy accessor for code that still uses List<MediaItem>
    val legacyResults: List<MediaItem> get() = results.map { it.item }
}

class SimilarImagesViewModel(
    private val queryUri: String,
    private val database: AppDatabase,
    private val mediaRepository: MediaRepository,
    private val modelManager: ModelManager,
    private val tagRepository: TagRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SimilarImagesState())
    val state: StateFlow<SimilarImagesState> = _state

    /** Tags currently attached to the source (query) image — used for batch apply. */
    private var sourceTags: List<Tag> = emptyList()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val activeModel = modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
                val modelVersion = activeModel?.metadata?.roomVersionKey ?: run {
                    _state.value = SimilarImagesState(isLoading = false, hasEmbedding = false)
                    return@withContext
                }

                val queryEmbedding = database.imageEmbeddingDao()
                    .getForAssetAndModel(queryUri, modelVersion)
                if (queryEmbedding == null) {
                    _state.value = SimilarImagesState(isLoading = false, hasEmbedding = false)
                    return@withContext
                }

                val service = SimilaritySearchService(database.imageEmbeddingDao())
                val similarResults = service.findSimilar(queryUri, modelVersion)

                val uris = similarResults.map { it.assetUri }
                val itemMap = if (uris.isEmpty()) emptyMap()
                    else mediaRepository.getMediaByUris(uris).associateBy { it.uri }

                val sourceItem = mediaRepository.getMediaByUris(listOf(queryUri)).firstOrNull()
                sourceTags = tagRepository.getTagsForMedia(queryUri).first()

                val scored = similarResults.mapIndexedNotNull { idx, sr ->
                    val item = itemMap[sr.assetUri] ?: return@mapIndexedNotNull null
                    SimilarMediaItem(item = item, similarity = sr.similarity, rank = idx + 1)
                }

                val filtered = applyFilter(scored, _state.value.mediaFilter)
                val sorted = applySort(filtered, _state.value.sortOrder, queryUri)

                _state.value = SimilarImagesState(
                    isLoading = false,
                    hasEmbedding = true,
                    sourceItem = sourceItem,
                    results = sorted,
                    sortOrder = _state.value.sortOrder,
                    mediaFilter = _state.value.mediaFilter
                )
            }
        }
    }

    fun setSortOrder(order: SimilarSortOrder) {
        val current = _state.value
        val sorted = applySort(current.results, order, queryUri)
        _state.value = current.copy(sortOrder = order, results = sorted)
    }

    fun setMediaFilter(filter: SimilarMediaFilter) {
        // Re-trigger a load with new filter (cheap — re-sorts in-memory)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val current = _state.value
                val activeModel = modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
                val modelVersion = activeModel?.metadata?.roomVersionKey ?: return@withContext
                val service = SimilaritySearchService(database.imageEmbeddingDao())
                val similarResults = service.findSimilar(queryUri, modelVersion)
                val uris = similarResults.map { it.assetUri }
                val itemMap = if (uris.isEmpty()) emptyMap()
                    else mediaRepository.getMediaByUris(uris).associateBy { it.uri }
                val scored = similarResults.mapIndexedNotNull { idx, sr ->
                    val item = itemMap[sr.assetUri] ?: return@mapIndexedNotNull null
                    SimilarMediaItem(item = item, similarity = sr.similarity, rank = idx + 1)
                }
                val filtered = applyFilter(scored, filter)
                val sorted = applySort(filtered, current.sortOrder, queryUri)
                _state.value = current.copy(mediaFilter = filter, results = sorted)
            }
        }
    }

    fun toggleSelection(uri: String) {
        val current = _state.value
        val selected = current.selectedUris.toMutableSet()
        if (uri in selected) selected.remove(uri) else selected.add(uri)
        _state.value = current.copy(selectedUris = selected)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedUris = emptySet())
    }

    /** Initiates the batch tag workflow — shows review dialog with source tags. */
    fun requestBatchTagApply() {
        if (_state.value.selectedUris.isEmpty()) return
        _state.value = _state.value.copy(pendingBatchTags = sourceTags)
    }

    fun cancelBatchTag() {
        _state.value = _state.value.copy(pendingBatchTags = null)
    }

    /** Applies source tags to all selected items, then clears selection. */
    fun confirmBatchTagApply() {
        val toApply = _state.value.pendingBatchTags ?: return
        val uris = _state.value.selectedUris.toList()
        viewModelScope.launch {
            tagRepository.bulkAttachTags(uris, toApply.map { it.name })
            _state.value = _state.value.copy(
                selectedUris = emptySet(),
                pendingBatchTags = null
            )
        }
    }

    private fun applyFilter(items: List<SimilarMediaItem>, filter: SimilarMediaFilter): List<SimilarMediaItem> {
        return when (filter) {
            SimilarMediaFilter.IMAGES_ONLY   -> items.filter { it.item.mediaType == "image" && !it.item.filename.endsWith(".gif", ignoreCase = true) }
            SimilarMediaFilter.INCLUDE_GIFS  -> items.filter { it.item.mediaType == "image" }
            SimilarMediaFilter.INCLUDE_VIDEOS -> items
        }
    }

    private fun applySort(items: List<SimilarMediaItem>, order: SimilarSortOrder, sourceUri: String): List<SimilarMediaItem> {
        val sourceFolder = sourceUri.substringBeforeLast("/")
        return when (order) {
            SimilarSortOrder.CLOSEST_FIRST   -> items.sortedByDescending { it.similarity }
            SimilarSortOrder.NEWEST_FIRST    -> items.sortedByDescending { it.item.deviceModifiedAt ?: 0L }
            SimilarSortOrder.SAME_FOLDER     -> items.sortedWith(
                compareByDescending<SimilarMediaItem> { it.item.uri.startsWith(sourceFolder) }
                    .thenByDescending { it.similarity }
            )
        }
    }
}

class SimilarImagesViewModelFactory(
    private val queryUri: String,
    private val database: AppDatabase,
    private val mediaRepository: MediaRepository,
    private val modelManager: ModelManager,
    private val tagRepository: TagRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SimilarImagesViewModel(queryUri, database, mediaRepository, modelManager, tagRepository) as T
}
