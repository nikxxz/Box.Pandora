package com.example.boxpandora.ui.main.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    //
    // Root-cause note — transient 0-item emission during sync:
    //   syncMediaStore() inserts albums with OnConflictStrategy.REPLACE.
    //   SQLite REPLACE = DELETE existing row + INSERT new row.
    //   The DELETE fires a FK SET_NULL cascade on media_index.album_id,
    //   which triggers Room's InvalidationTracker for media_index.
    //   getMediaByAlbumIdFlow re-executes while album_id is still NULL and
    //   returns 0 rows — visible as a grid flash — before the next insertAll
    //   re-assigns the correct album_id and InvalidationTracker fires again.
    //   The transformLatest debounce below absorbs this window (< 50 ms in practice).
    @OptIn(ExperimentalCoroutinesApi::class)
    val mediaItems: StateFlow<List<MediaItem>> =
        // filterNotNull: hold until albumId is resolved — eliminates the initial
        // flowOf(emptyList()) emission that the null-branch used to produce before
        // the init coroutine finished.  stateIn(initialValue) still shows [] on
        // first subscription; we just don't add a second redundant [] on top of it.
        combine(_albumId.filterNotNull(), _showHidden) { id, hidden -> Pair(id, hidden) }
        .flatMapLatest { (albumId, showHidden) ->
            var rawCount = 0
            repository.getMediaByAlbumIdFlow(albumId, showHidden)
                // --- TEMPORARY DEBUG LOG — remove once flash is confirmed fixed ---
                .onEach { list ->
                    val n = ++rawCount
                    if (n <= 3) {
                        Log.d("FolderDetailVM", "DB raw #$n albumId=$albumId size=${list.size} " +
                            "first10=${list.take(10).map { it.uri.substringAfterLast('/') }}")
                    }
                }
                // Suppress re-renders when sync rewrites the same items without
                // changing content or order. Comparison is ORDER-SENSITIVE so a
                // genuine reorder (e.g. new item inserted) does re-emit, but
                // identical DB re-reads do not. The DB query uses a deterministic
                // secondary sort (uri DESC) so the order is stable across re-queries.
                .distinctUntilChangedBy { list ->
                    list.map { Triple(it.uri, it.isHidden, it.isFavorite) }
                }
                // Absorb transient empty-list emissions caused by the FK SET_NULL cascade
                // described above.  Empty lists are delayed 300 ms; if a non-empty list
                // arrives before the delay expires (as it always does during a normal
                // sync), the delay is cancelled and the empty is never emitted.
                // Genuine empties (folder truly deleted/cleared) take 300 ms to appear —
                // acceptable UX vs. the alternative of the grid flashing blank then back.
                .transformLatest { list ->
                    if (list.isEmpty()) {
                        Log.d("FolderDetailVM", "⚠ empty list after distinct — debouncing 300ms")
                        delay(300)
                        Log.d("FolderDetailVM", "⚠ empty list emitted after debounce (genuine empty?)")
                    }
                    emit(list)
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Loaded on-demand: only starts when loadAlbums() is called (i.e. copy/move dialog opens).
    // Avoids an always-hot Flow that queries the albums table on every screen entry.
    private val _allAlbums = MutableStateFlow<List<Album>>(emptyList())
    val allAlbums: StateFlow<List<Album>> = _allAlbums
    private var albumsJob: Job? = null

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
