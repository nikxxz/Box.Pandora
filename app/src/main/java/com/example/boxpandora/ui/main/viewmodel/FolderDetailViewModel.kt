package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class FolderDetailViewModel(
    private val repository: MediaRepository,
    private val albumName: String
) : ViewModel() {

    val mediaItems: StateFlow<List<MediaItem>> = repository.getMediaByAlbumFlow(albumName)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
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
