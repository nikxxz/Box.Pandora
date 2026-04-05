package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.repository.LibraryHealthReport
import com.example.boxpandora.data.repository.TagRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class HealthScanState {
    object Idle : HealthScanState()
    object Scanning : HealthScanState()
    data class Done(val report: LibraryHealthReport) : HealthScanState()
}

data class FixResult(
    val countMismatchesFixed: Int = 0,
    val orphansRemoved: Int = 0,
    val unusedTagsDeleted: Int = 0,
    val duplicatesRemoved: Int = 0
) {
    val totalFixed: Int get() =
        countMismatchesFixed + orphansRemoved + unusedTagsDeleted + duplicatesRemoved
}

class LibraryHealthViewModel(
    private val tagRepository: TagRepository
) : ViewModel() {

    private val _scanState = MutableStateFlow<HealthScanState>(HealthScanState.Idle)
    val scanState: StateFlow<HealthScanState> = _scanState.asStateFlow()

    private val _isFixing = MutableStateFlow(false)
    val isFixing: StateFlow<Boolean> = _isFixing.asStateFlow()

    private val _lastFixResult = MutableStateFlow<FixResult?>(null)
    val lastFixResult: StateFlow<FixResult?> = _lastFixResult.asStateFlow()

    fun scan() {
        viewModelScope.launch {
            _scanState.value = HealthScanState.Scanning
            val report = tagRepository.scanHealth()
            _scanState.value = HealthScanState.Done(report)
        }
    }

    /** Fixes every detected issue in one pass, then re-scans. */
    fun fixAll() {
        viewModelScope.launch {
            _isFixing.value = true
            val countFixed   = tagRepository.fixUsageCounts()
            val orphansFixed = tagRepository.fixOrphanedAssociations()
            val unusedFixed  = tagRepository.deleteUnusedTags()
            val dupeFixed    = tagRepository.fixDuplicateTags()
            _lastFixResult.value = FixResult(countFixed, orphansFixed, unusedFixed, dupeFixed)
            _isFixing.value = false
            // Re-scan so the UI reflects the post-fix state
            scan()
        }
    }

    fun fixUsageCounts() {
        viewModelScope.launch {
            _isFixing.value = true
            val fixed = tagRepository.fixUsageCounts()
            _lastFixResult.value = FixResult(countMismatchesFixed = fixed)
            _isFixing.value = false
            scan()
        }
    }

    fun fixOrphans() {
        viewModelScope.launch {
            _isFixing.value = true
            val fixed = tagRepository.fixOrphanedAssociations()
            _lastFixResult.value = FixResult(orphansRemoved = fixed)
            _isFixing.value = false
            scan()
        }
    }

    fun deleteUnused() {
        viewModelScope.launch {
            _isFixing.value = true
            val fixed = tagRepository.deleteUnusedTags()
            _lastFixResult.value = FixResult(unusedTagsDeleted = fixed)
            _isFixing.value = false
            scan()
        }
    }

    fun fixDuplicates() {
        viewModelScope.launch {
            _isFixing.value = true
            val fixed = tagRepository.fixDuplicateTags()
            _lastFixResult.value = FixResult(duplicatesRemoved = fixed)
            _isFixing.value = false
            scan()
        }
    }

    fun clearFixResult() {
        _lastFixResult.value = null
    }
}

class LibraryHealthViewModelFactory(
    private val tagRepository: TagRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryHealthViewModel::class.java)) {
            return LibraryHealthViewModel(tagRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
