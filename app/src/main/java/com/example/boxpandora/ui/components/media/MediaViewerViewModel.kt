package com.example.boxpandora.ui.components.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MediaViewerViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val _allAlbums = MutableStateFlow<List<Album>>(emptyList())
    val allAlbums: StateFlow<List<Album>> = _allAlbums

    fun loadAlbums() {
        viewModelScope.launch {
            repository.getAlbumsFlow(true).collect { _allAlbums.value = it }
        }
    }

    fun deleteItem(item: MediaItem, onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (repository.deleteMediaItems(listOf(item.uri))) {
                onDeleted()
            }
        }
    }

    fun renameItem(item: MediaItem, newName: String) {
        viewModelScope.launch {
            repository.renameMediaItem(item.uri, newName)
        }
    }

    fun copyItem(item: MediaItem, destinationPath: String) {
        viewModelScope.launch {
            repository.copyMediaItems(listOf(item.uri), destinationPath)
        }
    }

    fun moveItem(item: MediaItem, destinationPath: String, onMoved: () -> Unit) {
        viewModelScope.launch {
            if (repository.moveMediaItems(listOf(item.uri), destinationPath)) {
                onMoved()
            }
        }
    }

    fun shareItem(context: Context, item: MediaItem) {
        val uri = getUriForFile(context, item) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (item.mediaType == "video") "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }

    fun openWith(context: Context, item: MediaItem) {
        val uri = getUriForFile(context, item) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (item.mediaType == "video") "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    }

    private fun getUriForFile(context: Context, item: MediaItem): Uri? {
        val file = item.filePath?.let { File(it) } ?: return null
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: Exception) {
            Uri.fromFile(file)
        }
    }
}

class MediaViewerViewModelFactory(
    private val repository: MediaRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewerViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
