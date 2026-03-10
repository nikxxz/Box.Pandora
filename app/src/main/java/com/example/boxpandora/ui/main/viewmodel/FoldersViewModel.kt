package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FoldersViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden

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

    fun refresh() {
        viewModelScope.launch {
            repository.syncMediaStore()
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
