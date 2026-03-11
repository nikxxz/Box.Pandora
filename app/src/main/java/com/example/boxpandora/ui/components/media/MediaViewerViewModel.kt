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
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.local.entity.TagChangeHistory
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.TagRepository
import com.example.boxpandora.data.local.dao.TagChangeHistoryDao
import com.example.boxpandora.data.local.dao.TagCooccurrenceDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MediaViewerViewModel(
    private val repository: MediaRepository,
    private val tagRepository: TagRepository,
    private val tagChangeHistoryDao: TagChangeHistoryDao,
    private val tagCooccurrenceDao: TagCooccurrenceDao
) : ViewModel() {

    private val _allAlbums = MutableStateFlow<List<Album>>(emptyList())
    val allAlbums: StateFlow<List<Album>> = _allAlbums

    val allTags = tagRepository.getAllTagsFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    fun loadAlbums() {
        viewModelScope.launch {
            repository.getAlbumsFlow(true).collect { _allAlbums.value = it }
        }
    }

    fun getTagsForMedia(uri: String) = tagRepository.getTagsForMedia(uri)

    fun getSuggestionsForMedia(uri: String) = MutableStateFlow<List<String>>(emptyList()).apply {
        viewModelScope.launch {
            value = tagRepository.getSuggestionsForMedia(uri)
        }
    }

    fun addTag(item: MediaItem, tagName: String) {
        viewModelScope.launch {
            tagRepository.attachTagToMedia(item.uri, tagName)
            logTagHistory(item, tagName, "media_attached")
            updateCooccurrences(item, tagName)
        }
    }

    fun acceptSuggestion(item: MediaItem, tagKey: String) {
        viewModelScope.launch {
            tagRepository.acceptSuggestion(item.uri, tagKey)
            logTagHistory(item, tagKey, "suggestion_accepted")
            updateCooccurrences(item, tagKey)
        }
    }

    fun rejectSuggestion(item: MediaItem, tagKey: String) {
        viewModelScope.launch {
            tagRepository.rejectSuggestion(item.uri, tagKey)
            // No history log needed for rejection usually, but could be added.
        }
    }

    private suspend fun logTagHistory(item: MediaItem, tagName: String, field: String) {
        val tag = tagRepository.resolveTagByName(tagName)
        if (tag != null) {
            tagChangeHistoryDao.insert(
                TagChangeHistory(
                    tagId = tag.id,
                    tagName = tag.name,
                    fieldChanged = field,
                    oldValue = null,
                    newValue = item.uri,
                    changeSource = "user",
                    reviewQueueId = null
                )
            )
        }
    }

    private suspend fun updateCooccurrences(item: MediaItem, tagName: String) {
        val tag = tagRepository.resolveTagByName(tagName) ?: return
        tagRepository.getTagsForMedia(item.uri).collect { currentTags ->
            currentTags.forEach { other ->
                if (other.id != tag.id) {
                    tagCooccurrenceDao.recordCooccurrence(tag.id, other.id)
                }
            }
        }
    }

    fun removeTag(item: MediaItem, tag: Tag) {
        viewModelScope.launch {
            tagRepository.detachTagFromMedia(item.uri, tag.id)
            tagChangeHistoryDao.insert(
                TagChangeHistory(
                    tagId = tag.id,
                    tagName = tag.name,
                    fieldChanged = "media_detached",
                    oldValue = item.uri,
                    newValue = "",
                    changeSource = "user",
                    reviewQueueId = null
                )
            )
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
    private val repository: MediaRepository,
    private val tagRepository: TagRepository,
    private val tagChangeHistoryDao: TagChangeHistoryDao,
    private val tagCooccurrenceDao: TagCooccurrenceDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewerViewModel(
                repository,
                tagRepository,
                tagChangeHistoryDao,
                tagCooccurrenceDao
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
