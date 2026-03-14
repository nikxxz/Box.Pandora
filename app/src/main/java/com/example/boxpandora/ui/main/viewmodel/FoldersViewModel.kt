package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.util.Formatters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class HomeTab {
    FOLDERS, ALL_MEDIA
}

sealed class AllMediaUiItem {
    data class Media(val item: MediaItem) : AllMediaUiItem()
    data class Header(val title: String) : AllMediaUiItem()
}

@OptIn(FlowPreview::class)
class FoldersViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden

    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val _currentTab = MutableStateFlow(HomeTab.FOLDERS)
    val currentTab: StateFlow<HomeTab> = _currentTab

    private val _searchParams = MutableStateFlow(MediaRepository.MediaSearchParams())
    val searchParams: StateFlow<MediaRepository.MediaSearchParams> = _searchParams

    private val _isSearchOpen = MutableStateFlow(false)
    val isSearchOpen: StateFlow<Boolean> = _isSearchOpen

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

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
    val albums: StateFlow<List<Album>> = combine(_showHidden, _sortOrder) { show, order ->
        show to order
    }.flatMapLatest { (show, order) ->
        repository.getAlbumsFlow(show).map { list ->
            val sorted = when (order) {
                SortOrder.DATE_DESC -> list.sortedByDescending { it.lastModifiedAt }
                SortOrder.DATE_ASC -> list.sortedBy { it.lastModifiedAt }
                SortOrder.NAME_ASC -> list.sortedBy { it.name }
                SortOrder.COUNT_DESC -> list.sortedByDescending { it.mediaCount }
                SortOrder.SIZE_DESC -> list.sortedByDescending { it.mediaCount } // Placeholder for actual size
                else -> list.sortedByDescending { it.lastModifiedAt }
            }
            sorted.sortedByDescending { it.isPinned }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val allMedia: Flow<PagingData<AllMediaUiItem>> = _showHidden.flatMapLatest { show ->
        repository.getAllMediaPaged(show).map { pagingData ->
            pagingData.map { AllMediaUiItem.Media(it) as AllMediaUiItem }
                .insertSeparators { before, after ->
                    if (after == null) return@insertSeparators null
                    val afterMedia = (after as? AllMediaUiItem.Media)?.item ?: return@insertSeparators null
                    val afterDate = afterMedia.deviceCreatedAt ?: 0L
                    
                    if (before == null) {
                        return@insertSeparators AllMediaUiItem.Header(Formatters.formatHeaderDate(afterDate))
                    }
                    
                    val beforeMedia = (before as? AllMediaUiItem.Media)?.item ?: return@insertSeparators null
                    val beforeDate = beforeMedia.deviceCreatedAt ?: 0L
                    
                    val beforeTitle = Formatters.formatHeaderDate(beforeDate)
                    val afterTitle = Formatters.formatHeaderDate(afterDate)
                    
                    if (beforeTitle != afterTitle) {
                        AllMediaUiItem.Header(afterTitle)
                    } else {
                        null
                    }
                }
        }
    }.cachedIn(viewModelScope)

    val totalMediaCount: StateFlow<Int> = albums.map { list ->
        list.sumOf { it.mediaCount }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setShowHidden(show: Boolean) {
        _showHidden.value = show
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setTab(tab: HomeTab) {
        _currentTab.value = tab
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

    fun selectAlbums(ids: Collection<Long>) {
        _selectedAlbumIds.value = ids.toSet()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            repository.syncMediaStore()
            _isRefreshing.value = false
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

    fun togglePinSelectedAlbums() {
        val ids = _selectedAlbumIds.value
        if (ids.isEmpty()) return
        val currentAlbums = albums.value.filter { it.id in ids }
        val anyUnpinned = currentAlbums.any { !it.isPinned }
        viewModelScope.launch {
            repository.setAlbumsPinned(ids.toList(), anyUnpinned)
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

    /**
     * Toggle favourite for a single media item (e.g. from long-press context menu).
     */
    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            repository.toggleFavorite(item)
        }
    }

    /**
     * Reactive stream of all favourite media items.
     */
    fun getFavoritesFlow(showHidden: Boolean = false) =
        repository.getFavoritesFlow(showHidden)
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
