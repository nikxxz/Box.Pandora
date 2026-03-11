package com.example.boxpandora.ui.common

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.data.repository.MediaRepository
import com.example.boxpandora.ui.components.grid.MediaThumbnail
import com.example.boxpandora.ui.main.TAG_CATEGORIES

private val MEDIA_FORMATS = listOf("all", "jpg", "png", "gif", "mp4", "webp")
private val MEDIA_TYPES    = listOf("all" to "All", "image" to "Images", "video" to "Videos")

// ─── Full search panel (bar + chips) ─────────────────────────────────────────

/**
 * The inline search bar + filter chips shown below the header when search is open.
 * Caller is responsible for animated visibility wrapping.
 */
@Composable
fun MediaSearchPanel(
    params:         MediaRepository.MediaSearchParams,
    onParamsChange: (MediaRepository.MediaSearchParams) -> Unit,
    onClose:        () -> Unit,
    modifier:       Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Search input ─────────────────────────────────────────────────────
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value         = params.query,
                onValueChange = { onParamsChange(params.copy(query = it)) },
                modifier      = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder   = {
                    Text(
                        "Search filenames, tags…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                leadingIcon   = {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                },
                trailingIcon  = if (params.query.isNotEmpty()) ({
                    IconButton(onClick = { onParamsChange(params.copy(query = "")) }) {
                        Icon(Icons.Default.Close, null)
                    }
                }) else null,
                singleLine    = true,
                shape         = RoundedCornerShape(12.dp),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    focusedTextColor     = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor   = MaterialTheme.colorScheme.onBackground
                )
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Hint for multi-tag ───────────────────────────────────────────────
        if (params.query.isNotEmpty()) {
            Text(
                text     = "Tip: separate terms with spaces or commas to match multiple tags",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }

        // ── Filter chips ─────────────────────────────────────────────────────
        SearchFilterChips(params = params, onParamsChange = onParamsChange)

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ─── Filter chip rows ─────────────────────────────────────────────────────────

@Composable
private fun SearchFilterChips(
    params:         MediaRepository.MediaSearchParams,
    onParamsChange: (MediaRepository.MediaSearchParams) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SearchChipRow(
            label    = "TYPE",
            chips    = MEDIA_TYPES.map { it.second },
            selected = MEDIA_TYPES.firstOrNull { it.first == params.type }?.second ?: "All",
            onSelect = { label ->
                val type = MEDIA_TYPES.firstOrNull { it.second == label }?.first ?: "all"
                onParamsChange(params.copy(type = type))
            }
        )
        SearchChipRow(
            label    = "FORMAT",
            chips    = MEDIA_FORMATS.map { if (it == "all") "All" else it.uppercase() },
            selected = if (params.format == "all") "All" else params.format.uppercase(),
            onSelect = { label ->
                val format = if (label == "All") "all" else label.lowercase()
                onParamsChange(params.copy(format = format))
            }
        )
        SearchChipRow(
            label    = "CATEGORY",
            chips    = TAG_CATEGORIES.map { if (it == "All") "All" else it.replaceFirstChar { c -> c.uppercase() } },
            selected = if (params.tagCategory == "All") "All" else params.tagCategory.replaceFirstChar { it.uppercase() },
            onSelect = { label ->
                val cat = if (label == "All") "All" else label.lowercase()
                onParamsChange(params.copy(tagCategory = cat))
            }
        )
    }
}

@Composable
private fun SearchChipRow(
    label:    String,
    chips:    List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelSmall.copy(
                fontSize      = 10.sp,
                letterSpacing = 1.sp,
                fontWeight    = FontWeight.SemiBold
            ),
            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.width(62.dp)
        )
        Row(
            modifier              = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            chips.forEach { chip ->
                val isSelected = chip == selected
                FilterChip(
                    selected = isSelected,
                    onClick  = { onSelect(chip) },
                    label    = {
                        Text(chip, style = MaterialTheme.typography.labelSmall)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        selectedLabelColor     = MaterialTheme.colorScheme.primary,
                        containerColor         = Color.Transparent,
                        labelColor             = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled             = true,
                        selected            = isSelected,
                        borderColor         = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        borderWidth         = 1.dp,
                        selectedBorderWidth = 1.dp
                    )
                )
            }
        }
    }
}

// ─── Search results grid ──────────────────────────────────────────────────────

/**
 * Reusable grid of search results. Replaces the normal screen content when search is active.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultsGrid(
    results:       List<MediaItem>,
    selectedUris:  Set<String> = emptySet(),
    isLoading:     Boolean,
    onPress:       (MediaItem) -> Unit,
    onLongPress:   (MediaItem) -> Unit,
    modifier:      Modifier = Modifier
) {
    when {
        isLoading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        results.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No results",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
        else -> {
            LazyVerticalGrid(
                columns        = GridCells.Fixed(3),
                modifier       = modifier.fillMaxSize(),
                contentPadding = PaddingValues(1.dp)
            ) {
                items(results, key = { it.uri }) { item ->
                    MediaThumbnail(
                        uri        = item.uri,
                        filePath   = item.filePath,
                        mediaType  = item.mediaType,
                        duration   = item.duration,
                        isFavorite = item.isFavorite,
                        isSelected = item.uri in selectedUris,
                        onPress    = { onPress(item) },
                        onLongPress = { onLongPress(item) }
                    )
                }
            }
        }
    }
}
