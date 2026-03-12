package com.example.boxpandora.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

@Composable
fun RenameDialog(
    initialName: String,
    title: String = "Rename",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initialName) { mutableStateOf(initialName) }

    AppDialog(onDismiss = onDismiss) {
        ModalHeader(title = title)
        ModalTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "Enter name"
        )
        DialogActionRow(
            dismissLabel = "Cancel",
            confirmLabel = "Confirm",
            onDismiss = onDismiss,
            onConfirm = {
                val next = text.trim()
                if (next.isNotEmpty()) {
                    onConfirm(next)
                } else {
                    onDismiss()
                }
            }
        )
    }
}

@Composable
fun DeleteConfirmationDialog(
    count: Int,
    isFolder: Boolean,
    title: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val resolvedTitle = title ?: if (isFolder) "Delete Folder" else "Delete Media"
    val message = when {
        title != null -> "Are you sure you want to perform this action? This cannot be undone."
        isFolder -> "Are you sure you want to delete $count selected folder(s) and all their contents? This action cannot be undone."
        else -> "Are you sure you want to delete $count selected item(s)? This action cannot be undone."
    }

    AppDialog(onDismiss = onDismiss) {
        ModalHeader(title = resolvedTitle, subtitle = message)
        DialogActionRow(
            dismissLabel = "Cancel",
            confirmLabel = "Delete",
            confirmDestructive = true,
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
    }
}

@Composable
fun FolderSelectorDialog(
    title: String,
    albums: List<Album>,
    onDismiss: () -> Unit,
    onConfirm: (Album) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredAlbums = remember(albums, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            albums
        } else {
            albums.filter {
                it.name.contains(query, ignoreCase = true) ||
                    (it.path?.contains(query, ignoreCase = true) == true)
            }
        }
    }

    AppModalSheet(onDismiss = onDismiss) {
        ModalHeader(title = title)

        if (albums.size > 8 || searchQuery.isNotBlank()) {
            ModalTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search folders"
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filteredAlbums, key = { it.id }) { album ->
                    ModalRichRow(
                        label = album.name,
                        supportingText = album.path ?: "No path available",
                        icon = Icons.Default.Folder,
                        onClick = { onConfirm(album) }
                    )
                }
            }
        }

        ModalFooterAction(label = "Cancel", onClick = onDismiss)
    }
}

@Composable
fun TagSelectorSheet(
    title: String,
    tags: List<Tag>,
    excludeTagId: Long? = null,
    onDismiss: () -> Unit,
    onConfirm: (Tag) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredTags = remember(tags, searchQuery, excludeTagId) {
        tags.filter {
            it.id != excludeTagId &&
                (searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true))
        }
    }

    AppModalSheet(onDismiss = onDismiss, skipPartiallyExpanded = false) {
        ModalHeader(title = title)
        ModalTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search tags"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filteredTags, key = { it.id }) { tag ->
                    ModalRichRow(
                        label = tag.name,
                        supportingText = tag.category.replaceFirstChar { it.uppercase() },
                        icon = Icons.AutoMirrored.Filled.Label,
                        trailingText = "${tag.usageCount}",
                        onClick = { onConfirm(tag) }
                    )
                }
            }
        }
        ModalFooterAction(label = "Cancel", onClick = onDismiss)
    }
}

@Composable
fun CategorySelectorSheet(
    currentCategory: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val categories = listOf(
        "people",
        "character",
        "style",
        "clothing",
        "pose",
        "place",
        "animal",
        "object",
        "mood",
        "misc"
    )

    AppModalSheet(onDismiss = onDismiss) {
        ModalHeader(title = "Change Category")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            categories.forEach { category ->
                ModalSelectableRow(
                    label = category.replaceFirstChar { it.uppercase() },
                    selected = category == currentCategory,
                    onClick = { onConfirm(category) }
                )
            }
        }
    }
}

@Composable
private fun DialogActionRow(
    dismissLabel: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmDestructive: Boolean = false
) {
    val tokens = boxPandoraModalTokens()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onDismiss) {
            Text(
                text = dismissLabel,
                color = tokens.secondaryText,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        TextButton(onClick = onConfirm) {
            Text(
                text = confirmLabel,
                color = if (confirmDestructive) tokens.destructiveAccent else tokens.selectedAccent,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            )
        }
    }
}
