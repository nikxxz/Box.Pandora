package com.example.boxpandora.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMoveRtl
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.boxpandora.data.local.entity.Album
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.data.manager.FileConflictResolution
import com.example.boxpandora.data.manager.PendingFileConflict
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        ModalHeader(
            title = "Change Category",
            subtitle = "Choose the group that best describes this tag."
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            categories.forEach { category ->
                ModalSelectableRow(
                    label = categorySheetTitle(category),
                    selected = category == currentCategory,
                    supportingText = categorySheetDescription(category),
                    icon = categorySheetIcon(category),
                    iconTint = boxPandoraModalTokens().bodyText,
                    onClick = { onConfirm(category) }
                )
                if (category != categories.last()) {
                    ModalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

private fun categorySheetTitle(category: String): String = when (category) {
    "people" -> "People"
    "character" -> "Character"
    "style" -> "Style"
    "clothing" -> "Clothing"
    "pose" -> "Pose"
    "place" -> "Place"
    "animal" -> "Animal"
    "object" -> "Object"
    "mood" -> "Mood"
    else -> "Misc"
}

private fun categorySheetDescription(category: String): String = when (category) {
    "people" -> "Faces, identities, portraits"
    "character" -> "Named personas and fictional roles"
    "style" -> "Aesthetic, color, and visual tone"
    "clothing" -> "Outfits, garments, and fashion"
    "pose" -> "Body position and stance"
    "place" -> "Locations, scenes, and settings"
    "animal" -> "Pets, wildlife, and creatures"
    "object" -> "Items, props, and products"
    "mood" -> "Emotion, vibe, and atmosphere"
    else -> "Everything that does not fit elsewhere"
}

private fun categorySheetIcon(category: String) = when (category) {
    "people" -> Icons.Default.Person
    "character" -> Icons.Default.Face
    "style" -> Icons.Default.Palette
    "clothing" -> Icons.Default.Style
    "pose" -> Icons.Default.FitnessCenter
    "place" -> Icons.Default.LocationOn
    "animal" -> Icons.Default.Pets
    "object" -> Icons.Default.Category
    "mood" -> Icons.Default.Mood
    else -> Icons.AutoMirrored.Filled.Label
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

/**
 * Shows when a copy or move destination file already exists.
 *
 * For a single-file operation the "Apply to all" row is hidden.
 * For multi-file batches the checkbox lets the user apply their choice to all
 * remaining conflicts without being asked again.
 *
 * @param conflict       Describes which file caused the conflict.
 * @param onResolve      Called when the user picks a resolution.
 *                       [applyToAll] is only meaningful when [PendingFileConflict.totalCount] > 1.
 * @param onDismiss      Called when the user taps outside the dialog or the Skip row.
 *                       Callers should treat dismiss as [FileConflictResolution.SKIP].
 */
@Composable
fun FileConflictDialog(
    conflict: PendingFileConflict,
    onResolve: (resolution: FileConflictResolution, applyToAll: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = boxPandoraModalTokens()
    val isMulti = conflict.totalCount > 1
    var applyToAll by remember { mutableStateOf(false) }

    AppDialog(onDismiss = onDismiss) {
        // ── Header ────────────────────────────────────────────────────────────
        val progress = if (isMulti) " (${conflict.currentIndex} of ${conflict.totalCount})" else ""
        ModalHeader(
            title = "File Already Exists$progress",
            subtitle = "\"${conflict.fileName}\" already exists in the destination folder."
        )

        // ── Options ───────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ModalActionRow(
                label = "Replace",
                supportingText = "The existing file will be overwritten",
                icon = Icons.Default.DriveFileMoveRtl,
                iconTint = tokens.destructiveAccent,
                destructive = false,
                onClick = { onResolve(FileConflictResolution.REPLACE, applyToAll) }
            )
            ModalDivider(modifier = Modifier.padding(start = 56.dp))
            ModalActionRow(
                label = "Keep Both",
                supportingText = "A new name will be assigned automatically",
                icon = Icons.Default.ContentCopy,
                iconTint = tokens.selectedAccent,
                onClick = { onResolve(FileConflictResolution.AUTO_RENAME, applyToAll) }
            )
            ModalDivider(modifier = Modifier.padding(start = 56.dp))
            ModalActionRow(
                label = "Skip",
                supportingText = "Leave the existing file untouched",
                icon = Icons.Default.SkipNext,
                iconTint = tokens.secondaryText,
                onClick = { onResolve(FileConflictResolution.SKIP, applyToAll) }
            )
        }

        // ── "Apply to all" (multi-file only) ─────────────────────────────────
        if (isMulti) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = applyToAll,
                    onCheckedChange = { applyToAll = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = tokens.selectedAccent,
                        checkmarkColor = tokens.background
                    )
                )
                Text(
                    text = "Apply to all remaining files",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.bodyText
                )
            }
        }

        ModalFooterAction(label = "Cancel", onClick = onDismiss)
    }
}
