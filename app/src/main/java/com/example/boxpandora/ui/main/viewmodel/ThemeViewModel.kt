package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.dao.UserPreferenceDao
import com.example.boxpandora.data.local.entity.UserPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ThemeMode {
    LIGHT, DARK, AUTO
}

enum class SortOrder {
    DATE_DESC, DATE_ASC, NAME_ASC, SIZE_DESC
}

private const val PREF_THEME_MODE  = "theme_mode"
private const val PREF_SHOW_HIDDEN = "show_hidden"
private const val PREF_GRID_SIZE   = "grid_size"
private const val PREF_SHOW_META   = "show_meta"
private const val PREF_SORT_ORDER  = "sort_order"

class ThemeViewModel(private val preferenceDao: UserPreferenceDao) : ViewModel() {

    private val _themeMode = MutableStateFlow(ThemeMode.AUTO)
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden

    private val _gridSize = MutableStateFlow(3)
    val gridSize: StateFlow<Int> = _gridSize

    private val _showMetadata = MutableStateFlow(false)
    val showMetadata: StateFlow<Boolean> = _showMetadata

    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    init {
        viewModelScope.launch {
            preferenceDao.getByKey(PREF_THEME_MODE)?.value?.let { saved ->
                runCatching { ThemeMode.valueOf(saved) }.getOrNull()?.let {
                    _themeMode.value = it
                }
            }
            preferenceDao.getByKey(PREF_SHOW_HIDDEN)?.value?.let { saved ->
                _showHidden.value = saved == "true"
            }
            preferenceDao.getByKey(PREF_GRID_SIZE)?.value?.let { saved ->
                _gridSize.value = saved.toIntOrNull() ?: 3
            }
            preferenceDao.getByKey(PREF_SHOW_META)?.value?.let { saved ->
                _showMetadata.value = saved == "true"
            }
            preferenceDao.getByKey(PREF_SORT_ORDER)?.value?.let { saved ->
                runCatching { SortOrder.valueOf(saved) }.getOrNull()?.let {
                    _sortOrder.value = it
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_THEME_MODE, value = mode.name))
        }
    }

    fun setShowHidden(show: Boolean) {
        _showHidden.value = show
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_SHOW_HIDDEN, value = show.toString()))
        }
    }

    fun setGridSize(size: Int) {
        _gridSize.value = size
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_GRID_SIZE, value = size.toString()))
        }
    }

    fun setShowMetadata(show: Boolean) {
        _showMetadata.value = show
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_SHOW_META, value = show.toString()))
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_SORT_ORDER, value = order.name))
        }
    }
}

class ThemeViewModelFactory(
    private val preferenceDao: UserPreferenceDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ThemeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ThemeViewModel(preferenceDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
