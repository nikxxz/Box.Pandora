package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class FoldersViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden

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
                    _searchResults.value = repository.searchMedia(params, albumId = null, showHidden = hidden)
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

    private val _selectedAlbumIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedAlbumIds: StateFlow<Set<Long>> = _selectedAlbumIds

    val isSelectionMode: StateFlow<Boolean> = _selectedAlbumIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val albums: StateFlow<List<Album>> = _showHidden
        .flatMapLatest { show ->
            repository.getAlbumsFlow(show)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setShowHidden(show: Boolean) {
        _showHidden.value = show
    }

    fun toggleSelection(albumId: Long) {
        val current = _selectedAlbumIds.value
        if (current.contains(albumId)) {
            _selectedAlbumIds.value = current - albumId
        } else {
            _selectedAlbumIds.value = current + albumId
        }
    }

    fun clearSelection() {
        _selectedAlbumIds.value = emptySet()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.syncMediaStore()
        }
    }

    fun deleteSelectedAlbums() {
        val ids = _selectedAlbumIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteAlbums(ids)
            clearSelection()
        }
    }

    fun renameSelectedAlbum(newName: String) {
        val id = _selectedAlbumIds.value.firstOrNull() ?: return
        viewModelScope.launch {
            repository.renameAlbum(id, newName)
            clearSelection()
        }
    }

    fun copySelectedAlbums(destinationPath: String) {
        val ids = _selectedAlbumIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.copyAlbums(ids, destinationPath)
            clearSelection()
        }
    }

    fun moveSelectedAlbums(destinationPath: String) {
        val ids = _selectedAlbumIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.moveAlbums(ids, destinationPath)
            clearSelection()
        }
    }

    fun toggleHiddenForSelected() {
        val selectedIds = _selectedAlbumIds.value
        if (selectedIds.isEmpty()) return
        
        val selectedAlbums = albums.value.filter { it.id in selectedIds }
        val targetHidden = !selectedAlbums.all { it.isHidden }
        
        viewModelScope.launch {
            repository.setAlbumsHidden(selectedIds.toList(), targetHidden)
            clearSelection()
        }
    }
}

class FoldersViewModelFactory(private val repository: MediaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FoldersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FoldersViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
