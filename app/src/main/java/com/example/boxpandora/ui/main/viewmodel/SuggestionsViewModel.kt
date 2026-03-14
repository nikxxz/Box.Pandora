package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.dao.TagSuggestionDao
import com.example.boxpandora.data.local.entity.TagSuggestion
import com.example.boxpandora.data.repository.TagRepository
import com.example.boxpandora.ml.config.AiSettings
import com.example.boxpandora.ml.config.AiSettingsRepository
import com.example.boxpandora.ml.manager.ModelManager
import com.example.boxpandora.ml.model.ModelCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SuggestionsUiState(
    val suggestions: List<TagSuggestion> = emptyList(),
    val isLoading: Boolean = true,
    val pendingCount: Int = 0
)

class SuggestionsViewModel(
    private val tagSuggestionDao: TagSuggestionDao,
    private val tagRepository: TagRepository,
    private val aiSettingsRepository: AiSettingsRepository,
    private val modelManager: ModelManager
) : ViewModel() {

    private val _state = MutableStateFlow(SuggestionsUiState())
    val state: StateFlow<SuggestionsUiState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)

            val settings = aiSettingsRepository.settings.first()
            val activeModel = modelManager.getActiveModel(ModelCategory.SCENE_EMBEDDING)
            val modelVersion = activeModel?.metadata?.roomVersionKey ?: return@launch run {
                _state.value = SuggestionsUiState(isLoading = false)
            }

            val suggestions = tagSuggestionDao.getPendingSuggestions(
                minScore = settings.confidenceThreshold.toDouble(),
                modelVersion = modelVersion
            )

            _state.value = SuggestionsUiState(
                suggestions = suggestions,
                isLoading = false,
                pendingCount = suggestions.size
            )
        }
    }

    fun accept(suggestion: TagSuggestion) {
        viewModelScope.launch {
            tagRepository.acceptSuggestion(suggestion.assetId, suggestion.tagKey)
            removeSuggestionFromState(suggestion)
        }
    }

    fun reject(suggestion: TagSuggestion) {
        viewModelScope.launch {
            tagRepository.rejectSuggestion(suggestion.assetId, suggestion.tagKey)
            removeSuggestionFromState(suggestion)
        }
    }

    private fun removeSuggestionFromState(suggestion: TagSuggestion) {
        _state.value = _state.value.copy(
            suggestions = _state.value.suggestions.filter { it.id != suggestion.id },
            pendingCount = (_state.value.pendingCount - 1).coerceAtLeast(0)
        )
    }
}

class SuggestionsViewModelFactory(
    private val tagSuggestionDao: TagSuggestionDao,
    private val tagRepository: TagRepository,
    private val aiSettingsRepository: AiSettingsRepository,
    private val modelManager: ModelManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SuggestionsViewModel(tagSuggestionDao, tagRepository, aiSettingsRepository, modelManager) as T
}
