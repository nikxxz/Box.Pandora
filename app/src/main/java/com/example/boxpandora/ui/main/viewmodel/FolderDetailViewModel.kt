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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
