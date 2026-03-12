package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class PerformanceOpState {
    object Idle    : PerformanceOpState()
    data class Running(val label: String) : PerformanceOpState()
    data class Done (val message: String) : PerformanceOpState()
    data class Error(val message: String) : PerformanceOpState()
}

class PerformanceViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _opState = MutableStateFlow<PerformanceOpState>(PerformanceOpState.Idle)
    val opState: StateFlow<PerformanceOpState> = _opState

    fun clearThumbnailCache() {
        if (_opState.value is PerformanceOpState.Running) return
        viewModelScope.launch {
            _opState.value = PerformanceOpState.Running("Clearing thumbnail cache…")
            try {
                repository.clearThumbnailCache()
                _opState.value = PerformanceOpState.Done("Thumbnail cache cleared.")
            } catch (e: Exception) {
                _opState.value = PerformanceOpState.Error("Failed: ${e.message}")
            }
        }
    }

    fun optimizeDatabase() {
        if (_opState.value is PerformanceOpState.Running) return
        viewModelScope.launch {
            _opState.value = PerformanceOpState.Running("Optimising database…")
            try {
                repository.optimizeDatabase()
                _opState.value = PerformanceOpState.Done("Database optimised successfully.")
            } catch (e: Exception) {
                _opState.value = PerformanceOpState.Error("Failed: ${e.message}")
            }
        }
    }

    fun runSmartCleanup() {
        if (_opState.value is PerformanceOpState.Running) return
        viewModelScope.launch {
            _opState.value = PerformanceOpState.Running("Running smart clean-up…")
            try {
                val removed = repository.runSmartCleanup()
                _opState.value = if (removed == 0)
                    PerformanceOpState.Done("Everything looks clean — nothing to remove.")
                else
                    PerformanceOpState.Done("Removed $removed orphaned record(s).")
            } catch (e: Exception) {
                _opState.value = PerformanceOpState.Error("Failed: ${e.message}")
            }
        }
    }

    fun clearResult() {
        _opState.value = PerformanceOpState.Idle
    }
}

class PerformanceViewModelFactory(private val repository: MediaRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PerformanceViewModel::class.java)) {
            return PerformanceViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
