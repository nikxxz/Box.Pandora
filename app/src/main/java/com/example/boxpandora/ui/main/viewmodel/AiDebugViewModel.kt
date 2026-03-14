package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.AppDatabase
import com.example.boxpandora.ml.engine.EmbeddingUtils
import com.example.boxpandora.ml.engine.SuggestionScore
import com.example.boxpandora.ml.engine.TagSuggestionEngine
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AiDebugInfo(
    val mediaUri: String,
    val hasEmbedding: Boolean,
    val modelVersion: String?,
    val embeddingDim: Int?,
    val prototypeCount: Int,
    val topMatches: List<SuggestionScore>,   // top 15, unfiltered (ignores threshold + rejections)
    val isLoading: Boolean = true
)

class AiDebugViewModel(
    private val mediaUri: String,
    private val database: AppDatabase,
    private val modelManager: ModelManager
) : ViewModel() {

    private val _info = MutableStateFlow(AiDebugInfo(mediaUri = mediaUri, hasEmbedding = false, modelVersion = null, embeddingDim = null, prototypeCount = 0, topMatches = emptyList()))
    val info: StateFlow<AiDebugInfo> = _info

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val activeModel = modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
                val modelVersion = activeModel?.metadata?.roomVersionKey

                val embeddingRow = if (modelVersion != null) {
                    database.imageEmbeddingDao().getForAssetAndModel(mediaUri, modelVersion)
                } else null

                val prototypeCount = database.tagPrototypeDao().getAll().size

                if (embeddingRow == null) {
                    _info.value = AiDebugInfo(
                        mediaUri = mediaUri,
                        hasEmbedding = false,
                        modelVersion = modelVersion,
                        embeddingDim = null,
                        prototypeCount = prototypeCount,
                        topMatches = emptyList(),
                        isLoading = false
                    )
                    return@withContext
                }

                val assetEmbedding = EmbeddingUtils.bytesToFloatArray(embeddingRow.embedding)

                val engine = TagSuggestionEngine(
                    tagPrototypeDao = database.tagPrototypeDao(),
                    tagDao = database.tagDao(),
                    tagCooccurrenceDao = database.tagCooccurrenceDao()
                )
                val prototypes = engine.loadPrototypes()

                val topMatches = engine.score(
                    assetEmbedding = assetEmbedding,
                    prototypes = prototypes,
                    existingTagKeys = emptySet(),   // debug: show all matches
                    rejectedTagKeys = emptySet(),
                    confidenceThreshold = 0f,       // debug: show everything
                    maxResults = 15
                )

                _info.value = AiDebugInfo(
                    mediaUri = mediaUri,
                    hasEmbedding = true,
                    modelVersion = modelVersion,
                    embeddingDim = assetEmbedding.size,
                    prototypeCount = prototypeCount,
                    topMatches = topMatches,
                    isLoading = false
                )
            }
        }
    }
}

class AiDebugViewModelFactory(
    private val mediaUri: String,
    private val database: AppDatabase,
    private val modelManager: ModelManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AiDebugViewModel(mediaUri, database, modelManager) as T
}
