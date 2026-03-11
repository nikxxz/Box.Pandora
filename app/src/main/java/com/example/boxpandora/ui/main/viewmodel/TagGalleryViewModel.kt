package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.RelatedTag
import com.example.boxpandora.data.repository.TagRepository
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

    private val _sortMode = MutableStateFlow(TagGallerySort.RECENTLY_ADDED)
    private val _gridMode = MutableStateFlow(GridMode.LARGE)
    private val _searchQuery = MutableStateFlow("")
    private val _filters = MutableStateFlow(TagFilters())
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())

    private val _tag = tagRepository.getTagFlow(tagId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    private val _mediaItems = mediaRepository.getMediaByTagFlow(tagId, showHidden)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _relatedTags = tagRepository.getRelatedTagsFlow(tagId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<TagGalleryUiState> = combine(
        combine(_tag, _mediaItems, _relatedTags) { t, m, r -> Triple(t, m, r) },
        combine(_sortMode, _gridMode, _searchQuery) { s, g, q -> Triple(s, g, q) },
        combine(_filters, _selectedUris) { f, sel -> Pair(f, sel) }
    ) { tmr, sgq, fsel ->
        val (tag, media, related) = tmr
        val (sort, grid, query) = sgq
        val (filters, selected) = fsel

        val filteredMedia = applyFiltersAndSearch(media, query, filters)
        TagGalleryUiState(
            tag = tag,
            media = sortMedia(filteredMedia, sort),
            relatedTags = related,
            sortMode = sort,
            gridMode = grid,
            filters = filters,
            searchQuery = query,
            selectedUris = selected,
            isLoading = tag == null && media.isEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TagGalleryUiState())

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

    fun toggleFavoriteSelected() = viewModelScope.launch {
        // Implement in MediaRepository if needed
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
