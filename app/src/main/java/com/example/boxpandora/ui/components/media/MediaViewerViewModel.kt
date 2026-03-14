package com.example.boxpandora.ui.components.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.local.entity.TagChangeHistory
import com.example.boxpandora.data.manager.FileConflictResolution
import com.example.boxpandora.data.manager.PendingFileConflict
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.data.repository.TagRepository
import com.example.boxpandora.data.local.dao.TagChangeHistoryDao
import com.example.boxpandora.data.local.dao.TagCooccurrenceDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
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

    private val _selectedMediaUri = MutableStateFlow<String?>(null)
    
    val tagsForSelectedMedia: StateFlow<List<Tag>> = _selectedMediaUri
        .filterNotNull()
        .flatMapLatest { uri -> 
            Log.d("MediaViewerVM", "Fetching tags for URI: $uri")
            tagRepository.getTagsForMedia(uri) 
        }
        .onEach { Log.d("MediaViewerVM", "Tags updated for current URI, count: ${it.size}") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val suggestionsForSelectedMedia: StateFlow<List<String>> = _selectedMediaUri
        .filterNotNull()
        .flatMapLatest { uri -> 
            Log.d("MediaViewerVM", "Fetching suggestions for URI: $uri")
            flow { emit(tagRepository.getSuggestionsForMedia(uri)) }
        }
        .onEach { Log.d("MediaViewerVM", "Suggestions updated for current URI, count: ${it.size}") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var albumsJob: Job? = null

    // ── File-conflict state ───────────────────────────────────────────────────
    private val _pendingConflict = MutableStateFlow<PendingFileConflict?>(null)
    val pendingConflict: StateFlow<PendingFileConflict?> = _pendingConflict

    private var conflictDeferred: CompletableDeferred<FileConflictResolution>? = null

    fun resolveConflict(resolution: FileConflictResolution, applyToAll: Boolean = false) {
        _pendingConflict.value = null
        conflictDeferred?.complete(resolution)
        conflictDeferred = null
    }

    private suspend fun awaitConflictResolution(
        fileName: String, destPath: String, itemIndex: Int, totalCount: Int
    ): FileConflictResolution {
        val deferred = CompletableDeferred<FileConflictResolution>()
        conflictDeferred = deferred
        _pendingConflict.value = PendingFileConflict(fileName, destPath, itemIndex, totalCount)
        return try { deferred.await() } finally { _pendingConflict.value = null }
    }

    fun loadAlbums() {
        if (albumsJob?.isActive == true) return
        albumsJob = viewModelScope.launch {
            Log.d("MediaViewerVM", "Loading albums...")
            repository.getAlbumsFlow(true).collect { _allAlbums.value = it }
        }
    }

    fun setCurrentMedia(uri: String) {
        if (_selectedMediaUri.value != uri) {
            Log.d("MediaViewerVM", "Setting current media URI: $uri")
            _selectedMediaUri.value = uri
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
        // Fix: Use first() for one-shot read instead of collect
        val currentTags = tagRepository.getTagsForMedia(item.uri).first()
        currentTags.forEach { other ->
            if (other.id != tag.id) {
                tagCooccurrenceDao.recordCooccurrence(tag.id, other.id)
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

    fun renameTag(tagId: Long, newName: String) {
        viewModelScope.launch {
            tagRepository.renameTag(tagId, newName)
        }
    }

    fun mergeTag(sourceTagId: Long, targetTagId: Long) {
        viewModelScope.launch {
            tagRepository.mergeTags(sourceTagId, targetTagId)
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
            try {
                repository.copyMediaItems(listOf(item.uri), destinationPath, ::awaitConflictResolution)
            } finally {
                _pendingConflict.value = null
            }
        }
    }

    fun moveItem(item: MediaItem, destinationPath: String, onMoved: () -> Unit) {
        viewModelScope.launch {
            try {
                if (repository.moveMediaItems(listOf(item.uri), destinationPath, ::awaitConflictResolution)) {
                    onMoved()
                }
            } finally {
                _pendingConflict.value = null
            }
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            val newFav = if (item.isFavorite == 1) 0 else 1
            repository.setMediaItemFavorite(item.uri, newFav)
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
