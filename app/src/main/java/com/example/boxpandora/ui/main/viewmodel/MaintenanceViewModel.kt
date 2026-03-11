package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MaintenanceViewModel(private val repository: MediaRepository) : ViewModel() {
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    fun reindex() {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.syncMediaStore(isFullScan = false) { msg, p ->
                _status.value = msg
                _progress.value = p
            }
            _isProcessing.value = false
        }
    }

    fun forceRecheck() {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.forceRecheck { msg, p ->
                _status.value = msg
                _progress.value = p
            }
            _isProcessing.value = false
        }
    }

    fun resetProgress() {
        _status.value = ""
        _progress.value = 0f
        _isProcessing.value = false
    }
}

class MaintenanceViewModelFactory(private val repository: MediaRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MaintenanceViewModel::class.java)) {
            return MaintenanceViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
