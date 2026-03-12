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
    DATE_DESC, DATE_ASC, NAME_ASC, SIZE_DESC, COUNT_DESC
}

enum class AccentColor(val displayName: String, val colorLong: Long) {
    EMBER_RED("Ember Red", 0xFFFF4A4AL),
    DEEP_ORANGE("Deep Orange", 0xFFFF6A3DL),
    AMBER_GOLD("Amber Gold", 0xFFF4B400L),
    EMERALD_GREEN("Emerald Green", 0xFF2ECC71L),
    TEAL("Teal", 0xFF1ABC9CL),
    AZURE_BLUE("Azure Blue", 0xFF3DA5FFL),
    INDIGO("Indigo", 0xFF6C5CE7L),
    ORCHID_PURPLE("Orchid Purple", 0xFFB76EFFL),
    ROSE_PINK("Rose Pink", 0xFFFF5C8AL),
    MONOCHROME_WHITE("Monochrome White", 0xFFE0E0E0L)
}

private const val PREF_THEME_MODE        = "theme_mode"
private const val PREF_SHOW_HIDDEN       = "show_hidden"
private const val PREF_GRID_SIZE         = "grid_size"
private const val PREF_SHOW_META         = "show_meta"
private const val PREF_SORT_ORDER        = "sort_order"
private const val PREF_GRADIENT          = "show_gradient"
private const val PREF_SHOW_IMAGES       = "show_images"
private const val PREF_SHOW_VIDEOS       = "show_videos"
private const val PREF_SHOW_GIFS         = "show_gifs"
private const val PREF_EXCLUDED_FOLDERS  = "excluded_folders" // newline-separated paths
private const val PREF_ACCENT_COLOR      = "accent_color"

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

    private val _showGradient = MutableStateFlow(true)
    val showGradient: StateFlow<Boolean> = _showGradient

    private val _showImages = MutableStateFlow(true)
    val showImages: StateFlow<Boolean> = _showImages

    private val _showVideos = MutableStateFlow(true)
    val showVideos: StateFlow<Boolean> = _showVideos

    private val _showGifs = MutableStateFlow(true)
    val showGifs: StateFlow<Boolean> = _showGifs

    private val _excludedFolders = MutableStateFlow<Set<String>>(emptySet())
    val excludedFolders: StateFlow<Set<String>> = _excludedFolders

    private val _accentColor = MutableStateFlow(AccentColor.EMBER_RED)
    val accentColor: StateFlow<AccentColor> = _accentColor

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
            preferenceDao.getByKey(PREF_GRADIENT)?.value?.let { saved ->
                _showGradient.value = saved != "false"
            }
            preferenceDao.getByKey(PREF_SHOW_IMAGES)?.value?.let { saved ->
                _showImages.value = saved != "false"
            }
            preferenceDao.getByKey(PREF_SHOW_VIDEOS)?.value?.let { saved ->
                _showVideos.value = saved != "false"
            }
            preferenceDao.getByKey(PREF_SHOW_GIFS)?.value?.let { saved ->
                _showGifs.value = saved != "false"
            }
            preferenceDao.getByKey(PREF_EXCLUDED_FOLDERS)?.value?.let { saved ->
                _excludedFolders.value = saved.split("\n").filter { it.isNotBlank() }.toSet()
            }
            preferenceDao.getByKey(PREF_ACCENT_COLOR)?.value?.let { saved ->
                runCatching { AccentColor.valueOf(saved) }.getOrNull()?.let {
                    _accentColor.value = it
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

    fun setShowGradient(show: Boolean) {
        _showGradient.value = show
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_GRADIENT, value = show.toString()))
        }
    }

    fun setShowImages(show: Boolean) {
        _showImages.value = show
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_SHOW_IMAGES, value = show.toString()))
        }
    }

    fun setShowVideos(show: Boolean) {
        _showVideos.value = show
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_SHOW_VIDEOS, value = show.toString()))
        }
    }

    fun setShowGifs(show: Boolean) {
        _showGifs.value = show
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_SHOW_GIFS, value = show.toString()))
        }
    }

    fun setAccentColor(color: AccentColor) {
        _accentColor.value = color
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_ACCENT_COLOR, value = color.name))
        }
    }

    fun addExcludedFolder(path: String) {
        val updated = _excludedFolders.value + path.trimEnd('/')
        _excludedFolders.value = updated
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_EXCLUDED_FOLDERS, value = updated.joinToString("\n")))
        }
    }

    fun removeExcludedFolder(path: String) {
        val updated = _excludedFolders.value - path
        _excludedFolders.value = updated
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_EXCLUDED_FOLDERS, value = updated.joinToString("\n")))
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
