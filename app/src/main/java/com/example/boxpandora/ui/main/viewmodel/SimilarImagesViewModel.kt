package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import com.example.boxpandora.ml.search.SimilaritySearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SimilarImagesState(
    val isLoading: Boolean = true,
    val hasEmbedding: Boolean = false,
    val results: List<MediaItem> = emptyList()
)

class SimilarImagesViewModel(
    private val queryUri: String,
    private val database: AppDatabase,
    private val mediaRepository: MediaRepository,
    private val modelManager: ModelManager
) : ViewModel() {

    private val _state = MutableStateFlow(SimilarImagesState())
    val state: StateFlow<SimilarImagesState> = _state

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val activeModel = modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
                val modelVersion = activeModel?.metadata?.roomVersionKey ?: run {
                    _state.value = SimilarImagesState(isLoading = false, hasEmbedding = false)
                    return@withContext
                }

                val queryEmbedding = database.imageEmbeddingDao()
                    .getForAssetAndModel(queryUri, modelVersion)
                if (queryEmbedding == null) {
                    _state.value = SimilarImagesState(isLoading = false, hasEmbedding = false)
                    return@withContext
                }

                val service = SimilaritySearchService(database.imageEmbeddingDao())
                val similarResults = service.findSimilar(queryUri, modelVersion)

                val uris = similarResults.map { it.assetUri }
                val items = if (uris.isEmpty()) emptyList()
                else mediaRepository.getMediaByUris(uris)
                    .sortedBy { item -> uris.indexOf(item.uri) }

                _state.value = SimilarImagesState(
                    isLoading = false,
                    hasEmbedding = true,
                    results = items
                )
            }
        }
    }
}

class SimilarImagesViewModelFactory(
    private val queryUri: String,
    private val database: AppDatabase,
    private val mediaRepository: MediaRepository,
    private val modelManager: ModelManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SimilarImagesViewModel(queryUri, database, mediaRepository, modelManager) as T
}
