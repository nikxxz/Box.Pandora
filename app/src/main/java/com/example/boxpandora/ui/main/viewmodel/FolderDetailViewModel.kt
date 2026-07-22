package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.manager.FileConflictResolution
import com.example.boxpandora.data.manager.PendingFileConflict
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.RichSuggestion
import com.example.boxpandora.data.repository.TagRepository
import kotlinx.coroutines.CompletableDeferred
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
    private val _showUntaggedOnly = MutableStateFlow(false)
    val showUntaggedOnly: StateFlow<Boolean> = _showUntaggedOnly.asStateFlow()

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris

    val isSelectionMode: StateFlow<Boolean> = _selectedUris
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedMediaItems: Flow<PagingData<MediaItem>> = combine(
        _showHidden,
        _showUntaggedOnly
    ) { showHidden, showUntaggedOnly -> showHidden to showUntaggedOnly }
        .flatMapLatest { (showHidden, showUntaggedOnly) ->
            repository.getMediaByAlbumPaged(albumId, showHidden, showUntaggedOnly)
        }
        .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val mediaItems: StateFlow<List<MediaItem>> = combine(
        _showHidden,
        _showUntaggedOnly
    ) { showHidden, showUntaggedOnly -> showHidden to showUntaggedOnly }
        .flatMapLatest { (showHidden, showUntaggedOnly) ->
            repository.getMediaByAlbumIdFlow(albumId, showHidden, showUntaggedOnly)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _allAlbums = MutableStateFlow<List<Album>>(emptyList())
    val allAlbums: StateFlow<List<Album>> = _allAlbums
    private var albumsJob: Job? = null

    val allTags = tagRepository.getAllTagsFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    /**
     * ML tag suggestions for the currently selected items.
     *
     * Fetches suggestions for up to 5 selected URIs and merges them (highest score wins
     * per tag key). Emits an empty list when nothing is selected.
     * Cancels and re-fetches automatically whenever the selection changes or refresh is triggered.
     */
    private val _suggestionRefreshTrigger = MutableStateFlow(0)

    private val _bulkSuggestionsLoading = MutableStateFlow(false)
    val bulkSuggestionsLoading: StateFlow<Boolean> = _bulkSuggestionsLoading.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val suggestionObjectsForSelected: StateFlow<List<RichSuggestion>> = combine(
        _selectedUris, _suggestionRefreshTrigger
    ) { uris, _ -> uris }
        .flatMapLatest { uris ->
            flow {
                if (uris.isEmpty()) { emit(emptyList<RichSuggestion>()); return@flow }
                _bulkSuggestionsLoading.value = true
                try {
                    val merged = uris.take(5)
                        .flatMap { uri -> tagRepository.getSuggestionObjectsForMedia(uri) }
                        .groupBy { it.tagKey }
                        .map { (_, group) -> group.maxByOrNull { it.score }!! }
                        .sortedByDescending { it.score }
                        .take(6)
                    emit(merged)
                } finally {
                    _bulkSuggestionsLoading.value = false
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Manually re-fetches AI suggestions from the database. */
    fun refreshBulkSuggestions() {
        _suggestionRefreshTrigger.value++
    }

    // ── Search ────────────────────────────────────────────────────────────────
    private val _searchParams = MutableStateFlow(MediaRepository.MediaSearchParams())
    val searchParams: StateFlow<MediaRepository.MediaSearchParams> = _searchParams

    private val _isSearchOpen = MutableStateFlow(false)
    val isSearchOpen: StateFlow<Boolean> = _isSearchOpen

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private data class SearchState(
        val params: MediaRepository.MediaSearchParams,
        val showHidden: Boolean,
        val showUntaggedOnly: Boolean,
        val isOpen: Boolean
    )

    init {
        viewModelScope.launch {
            combine(
                _searchParams.debounce(280L),
                _showHidden,
                _showUntaggedOnly,
                _isSearchOpen
            ) { params, hidden, untaggedOnly, isOpen ->
                SearchState(params, hidden, untaggedOnly, isOpen)
            }.collectLatest { state ->
                if (state.isOpen) {
                    _isSearching.value = true
                    _searchResults.value = repository.searchMedia(
                        state.params,
                        albumId = albumId,
                        showHidden = state.showHidden,
                        untaggedOnly = state.showUntaggedOnly
                    )
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

    fun toggleShowUntaggedOnly() {
        _showUntaggedOnly.value = !_showUntaggedOnly.value
    }

    fun toggleSelection(uri: String) {
        val current = _selectedUris.value
        _selectedUris.value = if (current.contains(uri)) current - uri else current + uri
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    fun selectItems(uris: Collection<String>) {
        _selectedUris.value = uris.toSet()
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

    // ── File-conflict state ───────────────────────────────────────────────────
    private val _pendingConflict = MutableStateFlow<PendingFileConflict?>(null)
    val pendingConflict: StateFlow<PendingFileConflict?> = _pendingConflict

    private var conflictDeferred: CompletableDeferred<FileConflictResolution>? = null
    private var bulkConflictResolution: FileConflictResolution? = null

    /**
     * Called from the UI (main thread) when the user picks a resolution for the shown conflict.
     * @param applyToAll when true, the same resolution is used for all remaining files silently.
     */
    fun resolveConflict(resolution: FileConflictResolution, applyToAll: Boolean) {
        if (applyToAll) bulkConflictResolution = resolution
        _pendingConflict.value = null
        conflictDeferred?.complete(resolution)
        conflictDeferred = null
    }

    /** Suspends the IO coroutine until the user makes a choice. */
    private suspend fun awaitConflictResolution(
        fileName: String, destPath: String, itemIndex: Int, totalCount: Int
    ): FileConflictResolution {
        bulkConflictResolution?.let { return it }
        val deferred = CompletableDeferred<FileConflictResolution>()
        conflictDeferred = deferred
        _pendingConflict.value = PendingFileConflict(fileName, destPath, itemIndex, totalCount)
        return try { deferred.await() } finally { _pendingConflict.value = null }
    }

    fun copySelectedItems(destinationPath: String) {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        bulkConflictResolution = null
        viewModelScope.launch {
            try {
                repository.copyMediaItems(uris, destinationPath, ::awaitConflictResolution)
            } finally {
                bulkConflictResolution = null
                _pendingConflict.value = null
            }
            clearSelection()
        }
    }

    fun moveSelectedItems(destinationPath: String) {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        bulkConflictResolution = null
        viewModelScope.launch {
            try {
                repository.moveMediaItems(uris, destinationPath, ::awaitConflictResolution)
            } finally {
                bulkConflictResolution = null
                _pendingConflict.value = null
            }
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
