package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.TagRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class TagSort(val label: String) {
    COUNT("Count"),
    NAME_AZ("A-Z"),
    RECENTLY_MODIFIED("Recently Modified"),
    RECENTLY_CREATED("Recently Created"),
    CATEGORY("Category")
}

enum class FeaturedMode(val label: String) {
    RECENTLY_ACTIVE("Recently Active"),
    MOST_USED("Most Used"),
    NEWLY_CREATED("Newly Created")
}

data class FeaturedTagUiModel(
    val tag: Tag,
    val coverMediaUri: String?,
    val relativeTime: String
)

data class TagScreenState(
    val allTags: List<Tag> = emptyList(),
    val filteredTags: List<Tag> = emptyList(),
    val currentSort: TagSort = TagSort.NAME_AZ,
    val categoryFilter: String = "All",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val featuredMode: FeaturedMode = FeaturedMode.RECENTLY_ACTIVE,
    val featuredVisible: Boolean = true
) {
    val totalCount: Int get() = allTags.size
}

class TagsViewModel(private val tagRepository: TagRepository) : ViewModel() {

    private val _sort = MutableStateFlow(TagSort.NAME_AZ)
    private val _categoryFilter = MutableStateFlow("All")
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchActive = MutableStateFlow(false)
    private val _featuredMode = MutableStateFlow(FeaturedMode.RECENTLY_ACTIVE)
    private val _featuredVisible = MutableStateFlow(true)

    private val _rawTags: StateFlow<List<Tag>> = tagRepository.getAllTagsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _featuredTags = MutableStateFlow<List<FeaturedTagUiModel>>(emptyList())
    val featuredTags: StateFlow<List<FeaturedTagUiModel>> = _featuredTags

    val uiState: StateFlow<TagScreenState> =
        combine(_rawTags, _sort, _categoryFilter, _searchQuery) { tags, sort, cat, search ->
            TagScreenState(
                allTags = tags,
                filteredTags = applyFilter(tags, sort, cat, search),
                currentSort = sort,
                categoryFilter = cat,
                searchQuery = search
            )
        }
        .combine(_isSearchActive) { state, active -> state.copy(isSearchActive = active) }
        .combine(_featuredVisible) { state, visible -> state.copy(featuredVisible = visible) }
        .combine(_featuredMode) { state, mode -> state.copy(featuredMode = mode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TagScreenState())

    init {
        viewModelScope.launch {
            combine(_rawTags, _featuredMode) { tags, mode -> Pair(tags, mode) }
                .collectLatest { (tags, mode) ->
                    val top4 = when (mode) {
                        FeaturedMode.RECENTLY_ACTIVE ->
                            tags.sortedByDescending { it.updatedAt ?: it.createdAt }.take(4)
                        FeaturedMode.MOST_USED ->
                            tags.sortedByDescending { it.usageCount }.take(4)
                        FeaturedMode.NEWLY_CREATED ->
                            tags.sortedByDescending { it.createdAt }.take(4)
                    }
                    _featuredTags.value = top4.map { tag ->
                        FeaturedTagUiModel(
                            tag = tag,
                            coverMediaUri = tagRepository.getCoverForTag(tag.id),
                            relativeTime = formatRelativeTime(tag.updatedAt ?: tag.createdAt)
                        )
                    }
                }
        }
    }

    fun setSort(sort: TagSort) { _sort.value = sort }
    fun setCategoryFilter(cat: String) { _categoryFilter.value = cat }
    fun setSearch(query: String) { _searchQuery.value = query }
    fun setFeaturedMode(mode: FeaturedMode) { _featuredMode.value = mode }
    fun toggleFeaturedVisible() { _featuredVisible.value = !_featuredVisible.value }

    fun toggleSearch() {
        val nowActive = !_isSearchActive.value
        _isSearchActive.value = nowActive
        if (!nowActive) _searchQuery.value = ""
    }

    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
    }

    fun renameTag(tagId: Long, newName: String) = viewModelScope.launch {
        tagRepository.renameTag(tagId, newName)
    }

    fun mergeTag(sourceTagId: Long, targetTagId: Long) = viewModelScope.launch {
        tagRepository.mergeTags(sourceTagId, targetTagId)
    }

    fun updateCategory(tagId: Long, category: String) = viewModelScope.launch {
        tagRepository.updateCategory(tagId, category)
    }

    fun addAlias(tagId: Long, alias: String) = viewModelScope.launch {
        tagRepository.addAlias(tagId, alias)
    }

    fun deleteTag(tagId: Long) = viewModelScope.launch {
        tagRepository.deleteTag(tagId)
    }

    private fun applyFilter(
        tags: List<Tag>,
        sort: TagSort,
        category: String,
        search: String
    ): List<Tag> {
        var list = tags

        if (category != "All") {
            list = list.filter { it.category.equals(category, ignoreCase = true) }
        }

        if (search.isNotBlank()) {
            val q = search.trim()
            list = list.filter {
                it.name.contains(q, ignoreCase = true) ||
                it.normalizedName.contains(q, ignoreCase = true) ||
                it.category.contains(q, ignoreCase = true)
            }
        }

        return when (sort) {
            TagSort.COUNT -> list.sortedByDescending { it.usageCount }
            TagSort.NAME_AZ -> list.sortedBy { it.name.lowercase() }
            TagSort.RECENTLY_MODIFIED -> list.sortedByDescending { it.updatedAt ?: it.createdAt }
            TagSort.RECENTLY_CREATED -> list.sortedByDescending { it.createdAt }
            TagSort.CATEGORY -> list.sortedWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
        }
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        val nowMs = System.currentTimeMillis()
        val diffDays = ((nowMs - timestampMs) / (1000L * 60 * 60 * 24)).toInt()
        return when {
            diffDays == 0 -> "Today"
            diffDays == 1 -> "Yesterday"
            diffDays < 7  -> "$diffDays days ago"
            else -> {
                val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
                val sameYear = cal.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
                val fmt = if (sameYear) "d MMM" else "d MMM yyyy"
                SimpleDateFormat(fmt, Locale.getDefault()).format(Date(timestampMs))
            }
        }
    }
}

class TagsViewModelFactory(
    private val tagRepository: TagRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TagsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TagsViewModel(tagRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
