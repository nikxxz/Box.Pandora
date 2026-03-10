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

private const val PREF_THEME_MODE  = "theme_mode"
private const val PREF_SHOW_HIDDEN = "show_hidden"

class ThemeViewModel(private val preferenceDao: UserPreferenceDao) : ViewModel() {

    private val _themeMode = MutableStateFlow(ThemeMode.AUTO)
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden

    init {
        // Load persisted preferences on first creation
        viewModelScope.launch {
            preferenceDao.getByKey(PREF_THEME_MODE)?.value?.let { saved ->
                runCatching { ThemeMode.valueOf(saved) }.getOrNull()?.let {
                    _themeMode.value = it
                }
            }
            preferenceDao.getByKey(PREF_SHOW_HIDDEN)?.value?.let { saved ->
                _showHidden.value = saved == "true"
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
