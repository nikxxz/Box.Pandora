package com.example.boxpandora.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.Tag
import com.example.boxpandora.ui.main.viewmodel.*

// ─── Category metadata ────────────────────────────────────────────────────────

private data class CategoryMeta(val icon: ImageVector, val color: Color)

private val CATEGORY_META: Map<String, CategoryMeta> = mapOf(
    "people"    to CategoryMeta(Icons.Default.Person,       Color(0xFF5C7CFA)),
    "character" to CategoryMeta(Icons.Default.Face,         Color(0xFF82C91E)),
    "style"     to CategoryMeta(Icons.Default.Palette,      Color(0xFFCC5DE8)),
    "clothing"  to CategoryMeta(Icons.Default.Style,         Color(0xFFFF6B6B)),
    "pose"      to CategoryMeta(Icons.Default.FitnessCenter, Color(0xFF339AF0)),
    "place"     to CategoryMeta(Icons.Default.LocationOn,   Color(0xFF20C997)),
    "animal"    to CategoryMeta(Icons.Default.Pets,         Color(0xFF94D82D)),
    "object"    to CategoryMeta(Icons.Default.Category,     Color(0xFFFF922B)),
    "mood"      to CategoryMeta(Icons.Default.Mood,         Color(0xFFF59F00)),
    "misc"      to CategoryMeta(Icons.AutoMirrored.Filled.Label, Color(0xFF868E96))
)

private fun categoryColor(category: String): Color =
    CATEGORY_META[category.lowercase()]?.color ?: Color(0xFF868E96)

private fun categoryIcon(category: String): ImageVector =
    CATEGORY_META[category.lowercase()]?.icon ?: Icons.AutoMirrored.Filled.Label

val TAG_CATEGORIES = listOf(
    "All", "people", "character", "style", "clothing", "pose", "place", "animal", "object", "mood", "misc"
)

private fun categoryDisplayName(cat: String) = cat.replaceFirstChar { it.uppercase() }

// ─── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    onTagClick: (Tag) -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val viewModel: TagsViewModel = viewModel(
        factory = TagsViewModelFactory(app.repository.tagRepository)
    )

    val uiState by viewModel.uiState.collectAsState()
    val featuredTags by viewModel.featuredTags.collectAsState()

    var showSortSheet by remember { mutableStateOf(false) }
    var quickActionsTag by remember { mutableStateOf<Tag?>(null) }
    var showFeaturedModeMenu by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        item(key = "header") {
            TagsHeader(
                isSearchActive = uiState.isSearchActive,
                onSearchToggle = { viewModel.toggleSearch() },
                onManageClick  = onOpenDrawer
            )
        }

        // ── Search bar ──────────────────────────────────────────────────────
        item(key = "search_bar") {
            AnimatedVisibility(
                visible = uiState.isSearchActive,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit  = shrinkVertically(tween(150)) + fadeOut(tween(150))
            ) {
                TagSearchBar(
                    query         = uiState.searchQuery,
                    onQueryChange = { viewModel.setSearch(it) },
                    onClose       = { viewModel.closeSearch() },
                    modifier      = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // ── Featured section ────────────────────────────────────────────────
        if (!uiState.isSearchActive) {
            item(key = "featured_header") {
                AnimatedVisibility(
                    visible = uiState.featuredVisible && featuredTags.isNotEmpty(),
                    enter   = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit    = shrinkVertically(tween(160)) + fadeOut(tween(160))
                ) {
                    FeaturedSectionHeader(
                        mode              = uiState.featuredMode,
                        isSectionVisible  = uiState.featuredVisible,
                        showModeMenu      = showFeaturedModeMenu,
                        onToggleModeMenu  = { showFeaturedModeMenu = !showFeaturedModeMenu },
                        onDismissModeMenu = { showFeaturedModeMenu = false },
                        onModeSelect      = { viewModel.setFeaturedMode(it); showFeaturedModeMenu = false },
                        onModeLabel       = uiState.featuredMode.label,
                        onToggleVisible   = { viewModel.toggleFeaturedVisible() }
                    )
                }
            }

            item(key = "featured_cards") {
                AnimatedVisibility(
                    visible = uiState.featuredVisible && featuredTags.isNotEmpty(),
                    enter   = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit    = shrinkVertically(tween(160)) + fadeOut(tween(160))
                ) {
                    FeaturedTagsGrid(
                        tags      = featuredTags,
                        onTagClick = onTagClick,
                        modifier  = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                    )
                }
            }

            // Collapsed "show" button when featured is hidden
            if (!uiState.featuredVisible && featuredTags.isNotEmpty()) {
                item(key = "featured_show") {
                    TextButton(
                        onClick = { viewModel.toggleFeaturedVisible() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "Show ${uiState.featuredMode.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // ── Controls row ────────────────────────────────────────────────────
        item(key = "controls") {
            TagsControlsRow(
                currentSort = uiState.currentSort,
                totalCount  = uiState.totalCount,
                onSortClick = { showSortSheet = true },
                modifier    = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // ── Category chips ──────────────────────────────────────────────────
        item(key = "chips") {
            TagCategoryChips(
                selected  = uiState.categoryFilter,
                onSelect  = { viewModel.setCategoryFilter(it) },
                modifier  = Modifier.padding(bottom = 4.dp)
            )
        }

        // ── Divider before list ──────────────────────────────────────────────
        item(key = "list_divider") {
            HorizontalDivider(
                color     = MaterialTheme.colorScheme.outlineVariant,
                modifier  = Modifier.padding(horizontal = 16.dp)
            )
        }

        // ── Tag rows ────────────────────────────────────────────────────────
        if (uiState.filteredTags.isEmpty()) {
            item(key = "empty") {
                TagEmptyState(
                    query    = uiState.searchQuery,
                    category = uiState.categoryFilter
                )
            }
        } else {
            items(uiState.filteredTags, key = { it.id }) { tag ->
                TagListRow(
                    tag         = tag,
                    onTap       = { onTagClick(tag) },
                    onLongPress = { quickActionsTag = tag }
                )
                HorizontalDivider(
                    color    = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 64.dp, end = 16.dp)
                )
            }
        }
    }

    // ── Sort bottom sheet ────────────────────────────────────────────────────
    if (showSortSheet) {
        TagSortSheet(
            current   = uiState.currentSort,
            onSelect  = { viewModel.setSort(it); showSortSheet = false },
            onDismiss = { showSortSheet = false }
        )
    }

    // ── Quick actions sheet ──────────────────────────────────────────────────
    quickActionsTag?.let { tag ->
        TagQuickActionsSheet(
            tag       = tag,
            onRename  = { /* future */ },
            onMerge   = { /* future */ },
            onDelete  = { viewModel.deleteTag(tag.id) },
            onDismiss = { quickActionsTag = null }
        )
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun TagsHeader(
    isSearchActive: Boolean,
    onSearchToggle: () -> Unit,
    onManageClick:  () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text  = "tags",
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight    = FontWeight.W200,
                letterSpacing = 4.sp,
                fontSize      = 34.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Row(
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            IconButton(onClick = onSearchToggle) {
                Icon(
                    imageVector        = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (isSearchActive) "Close search" else "Search tags",
                    modifier           = Modifier.size(22.dp),
                    tint               = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = onManageClick) {
                Icon(
                    imageVector        = Icons.Default.Tune,
                    contentDescription = "Manage tags",
                    modifier           = Modifier.size(22.dp),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Search bar ───────────────────────────────────────────────────────────────

@Composable
private fun TagSearchBar(
    query:         String,
    onQueryChange: (String) -> Unit,
    onClose:       () -> Unit,
    modifier:      Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    OutlinedTextField(
        value         = query,
        onValueChange = onQueryChange,
        modifier      = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder   = { Text("Search tags…", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
        leadingIcon   = {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        },
        trailingIcon  = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }) else null,
        singleLine    = true,
        shape         = RoundedCornerShape(12.dp),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            focusedTextColor     = MaterialTheme.colorScheme.onBackground,
            unfocusedTextColor   = MaterialTheme.colorScheme.onBackground
        )
    )
}

// ─── Featured section header ──────────────────────────────────────────────────

@Composable
private fun FeaturedSectionHeader(
    mode:              FeaturedMode,
    isSectionVisible:  Boolean,
    showModeMenu:      Boolean,
    onToggleModeMenu:  () -> Unit,
    onDismissModeMenu: () -> Unit,
    onModeSelect:      (FeaturedMode) -> Unit,
    onModeLabel:       String,
    onToggleVisible:   () -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box {
            TextButton(
                onClick       = onToggleModeMenu,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                Text(
                    text  = onModeLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.2.sp,
                        fontWeight    = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint   = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            DropdownMenu(
                expanded          = showModeMenu,
                onDismissRequest  = onDismissModeMenu
            ) {
                FeaturedMode.entries.forEach { m ->
                    DropdownMenuItem(
                        text    = { Text(m.label) },
                        onClick = { onModeSelect(m) },
                        trailingIcon = if (m == mode) ({
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        }) else null
                    )
                }
            }
        }

        TextButton(onClick = onToggleVisible) {
            Text(
                text  = if (isSectionVisible) "Hide" else "Show",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ─── Featured cards grid ──────────────────────────────────────────────────────

@Composable
private fun FeaturedTagsGrid(
    tags:      List<FeaturedTagUiModel>,
    onTagClick: (Tag) -> Unit,
    modifier:  Modifier = Modifier
) {
    val rows = tags.chunked(2)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowTags ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTags.forEach { item ->
                    FeaturedTagCard(
                        item      = item,
                        onClick   = { onTagClick(item.tag) },
                        modifier  = Modifier.weight(1f)
                    )
                }
                // Pad last row if odd count
                if (rowTags.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeaturedTagCard(
    item:     FeaturedTagUiModel,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = categoryColor(item.tag.category)

    Box(
        modifier = modifier
            .height(172.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick)
    ) {
        // Background: cover image or category-tinted fallback
        if (item.coverMediaUri != null) {
            AsyncImage(
                model               = item.coverMediaUri,
                contentDescription  = null,
                contentScale        = ContentScale.Crop,
                modifier            = Modifier.fillMaxSize().blur(12.dp)
            )
            // Dark overlay for text legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(accentColor.copy(alpha = 0.15f))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Icon(
                imageVector = categoryIcon(item.tag.category),
                contentDescription = null,
                tint        = accentColor.copy(alpha = 0.18f),
                modifier    = Modifier
                    .size(80.dp)
                    .align(Alignment.Center)
            )
        }

        // Gradient scrim at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
        )

        // Text content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text  = "${item.tag.usageCount}",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize   = 22.sp
                ),
                color = Color.White
            )
            Text(
                text  = "items",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text     = item.tag.name,
                style    = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color    = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text  = item.relativeTime,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

// ─── Controls row ─────────────────────────────────────────────────────────────

@Composable
private fun TagsControlsRow(
    currentSort: TagSort,
    totalCount:  Int,
    onSortClick: () -> Unit,
    modifier:    Modifier = Modifier
) {
    Row(
        modifier              = modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Sort pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            TextButton(
                onClick        = onSortClick,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text  = "Sort: ${currentSort.label}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint   = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Total count
        Text(
            text  = "$totalCount tags",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

// ─── Category chips ───────────────────────────────────────────────────────────

@Composable
private fun TagCategoryChips(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TAG_CATEGORIES.forEach { cat ->
            val isSelected = cat == selected
            val accentColor = if (cat == "All") MaterialTheme.colorScheme.primary
                              else categoryColor(cat)

            FilterChip(
                selected = isSelected,
                onClick  = { onSelect(cat) },
                label    = {
                    Text(
                        text  = categoryDisplayName(cat),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor         = accentColor.copy(alpha = 0.18f),
                    selectedLabelColor             = accentColor,
                    containerColor                 = Color.Transparent,
                    labelColor                     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled          = true,
                    selected         = isSelected,
                    borderColor      = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    selectedBorderColor = accentColor.copy(alpha = 0.4f),
                    borderWidth      = 1.dp,
                    selectedBorderWidth = 1.dp
                )
            )
        }
    }
}

// ─── Tag list row ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagListRow(
    tag:         Tag,
    onTap:       () -> Unit,
    onLongPress: () -> Unit
) {
    val isUnused = tag.usageCount == 0
    val accentColor = categoryColor(tag.category)
    val updatedLabel = if (tag.updatedAt != null) "Updated" else "Created"
    val timestampMs  = tag.updatedAt ?: tag.createdAt
    val dateStr      = formatShortDate(timestampMs)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Category icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accentColor.copy(alpha = if (isUnused) 0.08f else 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = categoryIcon(tag.category),
                contentDescription = null,
                tint               = accentColor.copy(alpha = if (isUnused) 0.45f else 1f),
                modifier           = Modifier.size(20.dp)
            )
        }

        // Name + metadata
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text     = tag.name,
                style    = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color    = MaterialTheme.colorScheme.onBackground.copy(alpha = if (isUnused) 0.55f else 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text  = "$updatedLabel $dateStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isUnused) 0.4f else 0.65f)
            )
        }

        // Count
        Text(
            text  = "${tag.usageCount}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isUnused) 0.35f else 0.8f)
        )
    }
}

private fun formatShortDate(timestampMs: Long): String {
    val cal     = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
    val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val fmt = if (cal.get(java.util.Calendar.YEAR) == thisYear) "d MMM" else "d MMM yyyy"
    return java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(timestampMs))
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun TagEmptyState(query: String, category: String) {
    Column(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp, horizontal = 32.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.Label,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
            modifier           = Modifier.size(56.dp)
        )
        Text(
            text  = when {
                query.isNotBlank()  -> "No tags matching \"$query\""
                category != "All"   -> "No tags in ${categoryDisplayName(category)}"
                else                -> "No tags yet"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ─── Sort bottom sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagSortSheet(
    current:   TagSort,
    onSelect:  (TagSort) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest  = onDismiss,
        sheetState        = sheetState,
        containerColor    = MaterialTheme.colorScheme.surface,
        dragHandle        = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text     = "Sort by",
                style    = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            TagSort.entries.forEach { sort ->
                val isSelected = sort == current
                ListItem(
                    headlineContent = {
                        Text(
                            sort.label,
                            color      = if (isSelected) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    trailingContent = if (isSelected) ({
                        Icon(
                            Icons.Default.Check,
                            null,
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { onSelect(sort) })
                )
            }
        }
    }
}

// ─── Quick actions sheet ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TagQuickActionsSheet(
    tag:       Tag,
    onRename:  () -> Unit,
    onMerge:   () -> Unit,
    onDelete:  () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle       = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Tag header
            Row(
                modifier              = Modifier.padding(vertical = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(categoryColor(tag.category).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = categoryIcon(tag.category),
                        null,
                        tint     = categoryColor(tag.category),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(tag.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        "${tag.usageCount} items",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            listOf(
                Triple(Icons.Default.Edit,   "Rename",   onRename),
                Triple(Icons.Default.CallMerge, "Merge into another tag", onMerge),
            ).forEach { (icon, label, action) ->
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent  = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
                    modifier        = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { action(); onDismiss() })
                )
            }

            if (tag.usageCount == 0) {
                ListItem(
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingContent  = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint     = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { onDelete(); onDismiss() })
                )
            }
        }
    }
}
