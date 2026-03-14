package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class FavoritesViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _showHidden = MutableStateFlow(false)

    private val _isSearchOpen = MutableStateFlow(false)
    val isSearchOpen: StateFlow<Boolean> = _isSearchOpen

    private val _searchParams = MutableStateFlow(MediaRepository.MediaSearchParams())
    val searchParams: StateFlow<MediaRepository.MediaSearchParams> = _searchParams

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris

    val isSelectionMode: StateFlow<Boolean> = _selectedUris
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val favorites: StateFlow<List<MediaItem>> = _showHidden
        .flatMapLatest { show -> repository.getFavoritesFlow(show) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                    val allFavs = repository.getFavoritesFlow(hidden).first()
                    val query = params.query.trim().lowercase()
                    _searchResults.value = if (query.isEmpty()) allFavs else {
                        allFavs.filter { item ->
                            item.filename.lowercase().contains(query) ||
                                item.albumName?.lowercase()?.contains(query) == true
                        }
                    }
                    _isSearching.value = false
                } else {
                    _searchResults.value = emptyList()
                    _isSearching.value = false
                }
            }
        }
    }

    fun setShowHidden(show: Boolean) {
        _showHidden.value = show
    }

    fun openSearch() { _isSearchOpen.value = true }
    fun closeSearch() {
        _isSearchOpen.value = false
        _searchParams.value = MediaRepository.MediaSearchParams()
    }
    fun updateSearchParams(params: MediaRepository.MediaSearchParams) {
        _searchParams.value = params
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

    fun removeFromFavoritesSelected() {
        val uris = _selectedUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = repository.getMediaByUris(uris)
            repository.batchToggleFavorite(items, toFavorite = false)
            clearSelection()
        }
    }
}

class FavoritesViewModelFactory(
    private val repository: MediaRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FavoritesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FavoritesViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
