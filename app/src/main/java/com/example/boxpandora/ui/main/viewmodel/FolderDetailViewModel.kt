package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class FolderDetailViewModel(
    private val repository: MediaRepository,
    private val tagRepository: TagRepository,
    private val albumId: Long
) : ViewModel() {

    private val _showHidden = MutableStateFlow(false)

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris

    val isSelectionMode: StateFlow<Boolean> = _selectedUris
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedMediaItems: Flow<PagingData<MediaItem>> = _showHidden
        .flatMapLatest { showHidden ->
            repository.getMediaByAlbumPaged(albumId, showHidden)
        }
        .cachedIn(viewModelScope)

    private val _allAlbums = MutableStateFlow<List<Album>>(emptyList())
    val allAlbums: StateFlow<List<Album>> = _allAlbums
    private var albumsJob: Job? = null

    val allTags = tagRepository.getAllTagsFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    // ── Search ────────────────────────────────────────────────────────────────
    private val _searchParams = MutableStateFlow(MediaRepository.MediaSearchParams())
    val searchParams: StateFlow<MediaRepository.MediaSearchParams> = _searchParams

    private val _isSearchOpen = MutableStateFlow(false)
    val isSearchOpen: StateFlow<Boolean> = _isSearchOpen

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    init {
        viewModelScope.launch {
            combine(
                _searchParams.debounce(280L),
                _showHidden,
                _isSearchOpen
            ) { params, hidden, isOpen ->
                Triple(params, hidden, isOpen)
            }.collectLatest { (params, hidden, isOpen) ->
                if (isOpen) {
                    _isSearching.value = true
                    _searchResults.value = repository.searchMedia(params, albumId = albumId, showHidden = hidden)
                    _isSearching.value = false
                } else {
                    _searchResults.value = emptyList()
                    _isSearching.value = false
                }
            }
        }
    }

    fun openSearch() { _isSearchOpen.value = true }
    fun closeSearch() {
        _isSearchOpen.value = false
        _searchParams.value = MediaRepository.MediaSearchParams()
    }
    fun updateSearchParams(params: MediaRepository.MediaSearchParams) {
        _searchParams.value = params
    }

    fun loadAlbums() {
        if (albumsJob != null) return
        albumsJob = viewModelScope.launch {
            repository.getAlbumsFlow(true).collect { _allAlbums.value = it }
        }
    }

    fun setShowHidden(show: Boolean) {
        _showHidden.value = show
    }

    fun toggleSelection(uri: String) {
        val current = _selectedUris.value
        _selectedUris.value = if (current.contains(uri)) current - uri else current + uri
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    fun deleteSelectedItems() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            repository.deleteMediaItems(uris)
            clearSelection()
        }
    }

    fun renameSelectedItem(newName: String) {
        val uri = _selectedUris.value.firstOrNull() ?: return
        viewModelScope.launch {
            repository.renameMediaItem(uri, newName)
            clearSelection()
        }
    }

    fun copySelectedItems(destinationPath: String) {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            repository.copyMediaItems(uris, destinationPath)
            clearSelection()
        }
    }

    fun moveSelectedItems(destinationPath: String) {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            repository.moveMediaItems(uris, destinationPath)
            clearSelection()
        }
    }

    fun toggleHiddenForSelected() {
        val selectedUris = _selectedUris.value
        viewModelScope.launch {
            repository.setMediaItemsHidden(selectedUris.toList(), true)
            repository.syncMediaStore()
            clearSelection()
        }
    }

    fun bulkAttachTags(tagNames: List<String>) {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            if (tagNames.isNotEmpty()) {
                tagRepository.bulkAttachTags(uris, tagNames)
            }
            clearSelection()
        }
    }

    /**
     * Toggle favourite for a single item.
     */
    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            repository.toggleFavorite(item)
        }
    }

    /**
     * Set favourite state for all currently selected items.
     * @param toFavorite true = mark as favourite, false = remove
     */
    fun batchToggleFavoriteSelected(toFavorite: Boolean) {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = repository.getMediaByUris(uris)
            repository.batchToggleFavorite(items, toFavorite)
            clearSelection()
        }
    }
}

class FolderDetailViewModelFactory(
    private val repository: MediaRepository,
    private val tagRepository: TagRepository,
    private val albumId: Long
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FolderDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FolderDetailViewModel(repository, tagRepository, albumId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
