package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FolderDetailViewModel(
    private val repository: MediaRepository,
    private val albumName: String
) : ViewModel() {

    private val _showHidden = MutableStateFlow(false)

    // Resolved once on init — albumId is the stable MediaStore bucket ID.
    // We look it up by name here so the media flow can query media_index
    // directly (by album_id) without an INNER JOIN on albums.
    private val _albumId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            _albumId.value = repository.getAlbumIdByName(albumName)
        }
    }

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris

    val isSelectionMode: StateFlow<Boolean> = _selectedUris
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Uses getMediaByAlbumIdFlow (queries ONLY media_index).
    // The previous getMediaByAlbumFlow used INNER JOIN with albums, meaning any
    // write to albums during a sync re-fired the flow → LazyGrid recomposed →
    // Coil reloaded thumbnails → visible flash. This flow never fires on album writes.
    @OptIn(ExperimentalCoroutinesApi::class)
    val mediaItems: StateFlow<List<MediaItem>> = combine(_albumId, _showHidden) { id, hidden ->
        Pair(id, hidden)
    }
        .flatMapLatest { (albumId, showHidden) ->
            if (albumId != null) {
                repository.getMediaByAlbumIdFlow(albumId, showHidden)
                    // Suppress re-renders when sync rewrites the same items without
                    // changing content or order. Comparison is ORDER-SENSITIVE so a
                    // genuine reorder (e.g. new item inserted) does re-emit, but
                    // identical DB re-reads do not. The DB query uses a deterministic
                    // secondary sort (uri DESC) so the order is stable across re-queries.
                    .distinctUntilChangedBy { list ->
                        list.map { Triple(it.uri, it.isHidden, it.isFavorite) }
                    }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Loaded lazily — only needed when copy/move dialogs open, not on screen entry.
    val allAlbums = repository.getAlbumsFlow(true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        val items = mediaItems.value.filter { it.uri in selectedUris }
        if (items.isEmpty()) return
        val targetHidden = items.any { it.isHidden == 0 }
        viewModelScope.launch {
            repository.setMediaItemsHidden(selectedUris.toList(), targetHidden)
            repository.syncMediaStore()
            clearSelection()
        }
    }
}

class FolderDetailViewModelFactory(
    private val repository: MediaRepository,
    private val albumName: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FolderDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FolderDetailViewModel(repository, albumName) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
