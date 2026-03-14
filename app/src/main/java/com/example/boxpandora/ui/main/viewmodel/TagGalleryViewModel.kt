package com.example.boxpandora.ui.main.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.RelatedTag
import com.example.boxpandora.data.repository.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class TagGallerySort(val label: String) {
    RECENTLY_ADDED("Recently Added"),
    RECENTLY_MODIFIED("Recently Modified"),
    OLDEST("Oldest"),
    NAME_AZ("A-Z"),
    RANDOM("Random")
}

enum class GridMode {
    COMPACT, // 4 columns
    LARGE    // 3 columns
}

data class TagFilters(
    val type: String = "all", // "all", "image", "video"
    val favoritesOnly: Boolean = false,
    val source: String = "all" // "all", "ai", "manual"
)

data class TagGalleryUiState(
    val tag: Tag? = null,
    val media: List<MediaItem> = emptyList(),
    val relatedTags: List<RelatedTag> = emptyList(),
    val allTags: List<Tag> = emptyList(),
    val sortMode: TagGallerySort = TagGallerySort.RECENTLY_ADDED,
    val gridMode: GridMode = GridMode.LARGE,
    val filters: TagFilters = TagFilters(),
    val searchQuery: String = "",
    val selectedUris: Set<String> = emptySet(),
    val isLoading: Boolean = true
) {
    val isSelectionMode: Boolean get() = selectedUris.isNotEmpty()
}

class TagGalleryViewModel(
    private val tagId: Long,
    private val mediaRepository: MediaRepository,
    private val tagRepository: TagRepository,
    private val showHidden: Boolean
) : ViewModel() {

    private val startTime = System.currentTimeMillis()

    private val _sortMode = MutableStateFlow(TagGallerySort.RECENTLY_ADDED)
    private val _gridMode = MutableStateFlow(GridMode.LARGE)
    private val _searchQuery = MutableStateFlow("")
    private val _filters = MutableStateFlow(TagFilters())
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())

    private val _tag = tagRepository.getTagFlow(tagId)
        .onEach { if (it != null) Log.d("TagGalleryVM", "Tag query first emission after ${System.currentTimeMillis() - startTime}ms") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Null = "not yet received from DB" — distinguishes a tag with no media from still-loading.
    private val _mediaItems: StateFlow<List<MediaItem>?> = mediaRepository.getMediaByTagFlow(tagId, showHidden)
        .onEach { Log.d("TagGalleryVM", "Media query first emission after ${System.currentTimeMillis() - startTime}ms, count: ${it.size}") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _relatedTags = tagRepository.getRelatedTagsFlow(tagId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Loaded lazily — only subscribed when a dialog that needs all tags is opened (merge/bulk-tag).
    // This avoids running SELECT * FROM tags on every screen entry.
    private val _loadAllTags = MutableStateFlow(false)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val _allTags = _loadAllTags
        .flatMapLatest { load ->
            if (load) tagRepository.getAllTagsFlow() else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun ensureAllTagsLoaded() {
        _loadAllTags.value = true
    }

    val uiState: StateFlow<TagGalleryUiState> = combine(
        combine(_tag, _mediaItems, _relatedTags) { t, m, r -> Triple(t, m, r) },
        combine(_sortMode, _gridMode, _searchQuery) { s, g, q -> Triple(s, g, q) },
        combine(_filters, _selectedUris, _allTags) { f, sel, all -> Triple(f, sel, all) }
    ) { tmr, sgq, fsa ->
        val (tag, rawMedia, related) = tmr
        val (sort, grid, query) = sgq
        val (filters, selected, all) = fsa

        // rawMedia == null  → DB hasn't emitted yet (still loading)
        // rawMedia == empty → DB responded: this tag genuinely has no media
        val media = rawMedia ?: emptyList()
        val filteredMedia = applyFiltersAndSearch(media, query, filters)

        TagGalleryUiState(
            tag = tag,
            media = sortMedia(filteredMedia, sort),
            relatedTags = related,
            allTags = all,
            sortMode = sort,
            gridMode = grid,
            filters = filters,
            searchQuery = query,
            selectedUris = selected,
            // Show spinner until BOTH tag AND media have had their first DB emission.
            isLoading = tag == null || rawMedia == null
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TagGalleryUiState())

    // --- Actions ---

    fun setSortMode(mode: TagGallerySort) { _sortMode.value = mode }
    fun setGridMode(mode: GridMode) { _gridMode.value = mode }
    fun toggleGridMode() {
        _gridMode.value = if (_gridMode.value == GridMode.LARGE) GridMode.COMPACT else GridMode.LARGE
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun updateFilters(filters: TagFilters) { _filters.value = filters }

    // Selection
    fun toggleSelection(uri: String) {
        _selectedUris.update { if (it.contains(uri)) it - uri else it + uri }
    }
    fun clearSelection() { _selectedUris.value = emptySet() }
    fun selectItems(uris: Collection<String>) { _selectedUris.value = uris.toSet() }

    // Tag Management
    fun updateDescription(description: String?) = viewModelScope.launch {
        tagRepository.updateDescription(tagId, description)
    }

    fun renameTag(newName: String) = viewModelScope.launch {
        tagRepository.renameTag(tagId, newName)
    }

    fun mergeTag(targetTagId: Long) = viewModelScope.launch {
        tagRepository.mergeTags(tagId, targetTagId)
    }

    fun updateCategory(category: String) = viewModelScope.launch {
        tagRepository.updateCategory(tagId, category)
    }

    fun addAlias(alias: String) = viewModelScope.launch {
        tagRepository.addAlias(tagId, alias)
    }

    fun deleteTag() = viewModelScope.launch {
        tagRepository.deleteTag(tagId)
    }

    // Batch Media Actions
    fun removeTagFromSelected() = viewModelScope.launch {
        val uris = _selectedUris.value.toList()
        tagRepository.removeTagFromMediaBulk(uris, tagId)
        clearSelection()
    }

    fun addTagsToSelected(tagNames: List<String>) = viewModelScope.launch {
        val uris = _selectedUris.value.toList()
        tagRepository.bulkAttachTags(uris, tagNames)
        clearSelection()
    }

    /**
     * Toggle favourite for a single item.
     */
    fun toggleFavorite(item: MediaItem) = viewModelScope.launch {
        mediaRepository.toggleFavorite(item)
    }

    /**
     * Toggle favourite for all selected items.
     * If any selected item is NOT a favourite, all become favourited; otherwise all are unfavourited.
     */
    fun toggleFavoriteSelected() = viewModelScope.launch {
        val selectedUris = _selectedUris.value.toList()
        if (selectedUris.isEmpty()) return@launch
        val items = mediaRepository.getMediaByUris(selectedUris)
        val toFavorite = items.any { it.isFavorite != 1 }
        mediaRepository.batchToggleFavorite(items, toFavorite)
        clearSelection()
    }

    fun deleteSelectedMedia() = viewModelScope.launch {
        mediaRepository.deleteMediaItems(_selectedUris.value.toList())
        clearSelection()
    }

    private fun applyFiltersAndSearch(media: List<MediaItem>, query: String, filters: TagFilters): List<MediaItem> {
        return media.filter { item ->
            val matchesType = when (filters.type) {
                "image" -> item.mediaType == "image"
                "video" -> item.mediaType == "video"
                else -> true
            }
            val matchesFav = if (filters.favoritesOnly) item.isFavorite == 1 else true
            
            val matchesQuery = if (query.isBlank()) true else {
                item.filename.contains(query, ignoreCase = true) ||
                (item.notes?.contains(query, ignoreCase = true) ?: false)
            }

            matchesType && matchesFav && matchesQuery
        }
    }

    private fun sortMedia(media: List<MediaItem>, sort: TagGallerySort): List<MediaItem> {
        return when (sort) {
            TagGallerySort.RECENTLY_ADDED -> media.sortedByDescending { it.indexedAt }
            TagGallerySort.RECENTLY_MODIFIED -> media.sortedByDescending { it.deviceModifiedAt ?: it.deviceCreatedAt }
            TagGallerySort.OLDEST -> media.sortedBy { it.deviceCreatedAt }
            TagGallerySort.NAME_AZ -> media.sortedBy { it.filename.lowercase() }
            TagGallerySort.RANDOM -> media.shuffled()
        }
    }
}

class TagGalleryViewModelFactory(
    private val tagId: Long,
    private val mediaRepository: MediaRepository,
    private val tagRepository: TagRepository,
    private val showHidden: Boolean
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TagGalleryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TagGalleryViewModel(tagId, mediaRepository, tagRepository, showHidden) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
