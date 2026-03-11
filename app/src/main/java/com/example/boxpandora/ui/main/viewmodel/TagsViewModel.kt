package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.repository.TagRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagsViewModel(
    private val tagRepository: TagRepository
) : ViewModel() {

    val allTags: StateFlow<List<Tag>> = tagRepository.getAllTagsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun renameTag(tagId: Long, newName: String) {
        viewModelScope.launch {
            tagRepository.renameTag(tagId, newName)
        }
    }

    fun mergeTags(sourceTagId: Long, targetTagId: Long) {
        viewModelScope.launch {
            tagRepository.mergeTags(sourceTagId, targetTagId)
        }
    }

    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            // Usually we might want to just delete the tag and its relations
            // TagRepository merge handles deletion of source, but for straight delete:
            // We can add a delete method to TagRepository if needed.
            // For now, let's assume rename/merge are the primary management tools.
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
