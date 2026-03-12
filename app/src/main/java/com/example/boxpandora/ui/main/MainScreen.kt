package com.example.boxpandora.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.components.media.MediaViewer
import com.example.boxpandora.ui.common.AppDialog
import com.example.boxpandora.ui.common.ModalHeader
import com.example.boxpandora.ui.main.viewmodel.*
import com.example.boxpandora.ui.settings.*
import com.example.boxpandora.ui.theme.BoxPandoraTheme
import com.example.boxpandora.ui.theme.PandoraMotion
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
import com.example.boxpandora.ui.theme.detailBackEnter
import com.example.boxpandora.ui.theme.detailBackExit
import com.example.boxpandora.ui.theme.detailForwardEnter
import com.example.boxpandora.ui.theme.detailForwardExit
import com.example.boxpandora.ui.theme.drawerEnterTransition
import com.example.boxpandora.ui.theme.drawerExitTransition
import com.example.boxpandora.ui.theme.inlineRevealEnter
import com.example.boxpandora.ui.theme.inlineRevealExit
import com.example.boxpandora.ui.theme.modalScreenEnter
import com.example.boxpandora.ui.theme.modalScreenExit
import com.example.boxpandora.ui.theme.overlayFadeEnter
import com.example.boxpandora.ui.theme.overlayFadeExit
import com.example.boxpandora.ui.theme.panelEnterTransition
import com.example.boxpandora.ui.theme.panelExitTransition

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val themeViewModel: ThemeViewModel = viewModel(
        factory = ThemeViewModelFactory(app.database.userPreferenceDao())
    )
    val maintenanceViewModel: MaintenanceViewModel = viewModel(
        factory = MaintenanceViewModelFactory(app.repository)
    )

    val themeMode     by themeViewModel.themeMode.collectAsState()
    val showHidden    by themeViewModel.showHidden.collectAsState()
    val gridSize      by themeViewModel.gridSize.collectAsState()
    val showMetadata  by themeViewModel.showMetadata.collectAsState()
    val sortOrder     by themeViewModel.sortOrder.collectAsState()
    val showGradient  by themeViewModel.showGradient.collectAsState()

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var activeMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isDrawerOpen by remember { mutableStateOf(false) }

    val isProcessing by maintenanceViewModel.isProcessing.collectAsState()
    val status by maintenanceViewModel.status.collectAsState()
    val progress by maintenanceViewModel.progress.collectAsState()

    if (isDrawerOpen) {
        BackHandler { isDrawerOpen = false }
    }

    BoxPandoraTheme(themeMode = themeMode, showGradient = showGradient) {
        Box(modifier = Modifier.fillMaxSize()) {

            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    AnimatedVisibility(
                        visible = bottomNavItems.any { it.route == currentRoute },
                        enter = panelEnterTransition(),
                        exit  = panelExitTransition()
                    ) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                            tonalElevation = 0.dp,
                            modifier = Modifier.height(80.dp)
                        ) {
                            bottomNavItems.forEach { screen ->
                                val selected = currentRoute == screen.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (currentRoute != screen.route) {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.startDestinationId) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = {
                                        Text(
                                            text = screen.label,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.W800,
                                                letterSpacing = 2.sp,
                                                fontSize = 14.sp
                                            ),
                                            color = if (selected)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = Color.Transparent
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            bottom = if (bottomNavItems.any { it.route == currentRoute })
                                innerPadding.calculateBottomPadding() else 0.dp
                        )
                ) {
                    NavigationGraph(
                        navController      = navController,
                        onOpenDrawer       = { isDrawerOpen = true },
                        activeMediaItems   = activeMediaItems,
                        onUpdateMediaItems = { activeMediaItems = it },
                        showHidden         = showHidden
                    )
                }
            }

            AnimatedVisibility(
                visible = isDrawerOpen,
                enter   = overlayFadeEnter(),
                exit    = overlayFadeExit()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(0.6f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isDrawerOpen = false }
                )
            }

            AnimatedVisibility(
                visible = isDrawerOpen,
                enter   = drawerEnterTransition(),
                exit    = drawerExitTransition()
            ) {
                SettingsDrawer(
                    themeMode      = themeMode,
                    showHidden     = showHidden,
                    gridSize       = gridSize,
                    showMetadata   = showMetadata,
                    sortOrder      = sortOrder,
                    showGradient   = showGradient,
                    onThemeSet     = { themeViewModel.setThemeMode(it) },
                    onToggleHide   = { themeViewModel.setShowHidden(!showHidden) },
                    onGridSizeSet  = { themeViewModel.setGridSize(it) },
                    onToggleMeta   = { themeViewModel.setShowMetadata(!showMetadata) },
                    onSortOrderSet = { themeViewModel.setSortOrder(it) },
                    onToggleGradient = { themeViewModel.setShowGradient(!showGradient) },
                    onOpenFullSettings = {
                        isDrawerOpen = false
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            if (isProcessing || (progress > 0f && progress < 1f) || (status.isNotBlank() && !isProcessing)) {
                SyncProgressModal(
                    status = status,
                    progress = progress,
                    isProcessing = isProcessing,
                    onDismiss = { maintenanceViewModel.resetProgress() }
                )
            }
        }
    }
}

@Composable
fun SettingsDrawer(
    themeMode: ThemeMode,
    showHidden: Boolean,
    gridSize: Int,
    showMetadata: Boolean,
    sortOrder: SortOrder,
    showGradient: Boolean,
    onThemeSet: (ThemeMode) -> Unit,
    onToggleHide: () -> Unit,
    onGridSizeSet: (Int) -> Unit,
    onToggleMeta: () -> Unit,
    onSortOrderSet: (SortOrder) -> Unit,
    onToggleGradient: () -> Unit,
    onOpenFullSettings: () -> Unit
) {
    val tokens = boxPandoraModalTokens()

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(296.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(44.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(tokens.handleColor)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Quick Options",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )

            Text(
                text = "Display and library controls you use most often.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
            )

            Spacer(Modifier.height(24.dp))

            QuickSectionHeader("Appearance")
            
            SegmentedSelector(
                label = "Theme Mode",
                options = listOf(ThemeMode.AUTO, ThemeMode.LIGHT, ThemeMode.DARK),
                selected = themeMode,
                onSelected = onThemeSet
            ) { mode ->
                when(mode) {
                    ThemeMode.AUTO -> "Auto"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                }
            }

            Spacer(Modifier.height(16.dp))

            CompactToggleRow(
                title = "Subtle background gradient",
                checked = showGradient,
                onCheckedChange = { onToggleGradient() }
            )

            SegmentedSelector(
                label = "Grid Size",
                options = listOf(2, 3, 4, 5),
                selected = gridSize,
                onSelected = onGridSizeSet
            ) { it.toString() }

            Spacer(Modifier.height(24.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f))

            Spacer(Modifier.height(20.dp))

            QuickSectionHeader("Content")

            CompactToggleRow(
                title = "Show hidden files",
                checked = showHidden,
                onCheckedChange = { onToggleHide() }
            )

            CompactToggleRow(
                title = "Metadata overlay",
                checked = showMetadata,
                onCheckedChange = { onToggleMeta() }
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Sort Folders/Files by",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            val sortOptions = listOf(
                SortOrder.DATE_DESC to "Activity",
                SortOrder.NAME_ASC to "Name",
                SortOrder.COUNT_DESC to "Items",
                SortOrder.SIZE_DESC to "Size"
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sortOptions.forEach { (order, label) ->
                    val isSelected = sortOrder == order
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSortOrderSet(order) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            
            TextButton(
                onClick = onOpenFullSettings,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text("Open Full Settings")
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun QuickSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
fun <T> SegmentedSelector(
    label: String,
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    optionLabel: (T) -> String
) {
    val tokens = boxPandoraModalTokens()

    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = tokens.iconBackgroundNeutral,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { onSelected(option) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = optionLabel(option),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = boxPandoraModalTokens()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.graphicsLayer(scaleX = 0.75f, scaleY = 0.75f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.selectedAccent,
                    checkedTrackColor = tokens.selectedAccent.copy(alpha = 0.28f),
                    uncheckedThumbColor = tokens.iconBackgroundNeutral,
                    uncheckedTrackColor = tokens.rowPressedBackground
                )
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = { content() }
    )
}

@Composable
fun SyncProgressModal(
    status: String,
    progress: Float,
    isProcessing: Boolean,
    onDismiss: () -> Unit
) {
    val tokens = boxPandoraModalTokens()

    AppDialog(
        onDismiss = onDismiss,
        dismissOnClickOutside = !isProcessing,
        dismissOnBackPress = !isProcessing
    ) {
        ModalHeader(title = "Media Library Sync")
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${(progress * 100).toInt()}%",
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        if (!isProcessing) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Done", color = tokens.selectedAccent)
                    }
                }
            }
        }
    }
}

@Composable
fun NavigationGraph(
    navController: NavHostController,
    onOpenDrawer: () -> Unit,
    activeMediaItems: List<MediaItem>,
    onUpdateMediaItems: (List<MediaItem>) -> Unit,
    showHidden: Boolean
) {
    NavHost(navController = navController, startDestination = Screen.Folders.route) {
        composable(Screen.Folders.route) {
            FoldersScreen(
                showHidden    = showHidden,
                onFolderClick = { album ->
                    navController.navigate("folder_detail/${album.id}/${album.name}")
                },
                onMediaClick = { items, index ->
                    onUpdateMediaItems(items)
                    navController.navigate("media_viewer/$index")
                },
                onOpenDrawer  = onOpenDrawer
            )
        }
        composable(Screen.Favorites.route) {
            FavoritesScreen(
                showHidden   = showHidden,
                onMediaClick = { items, index ->
                    onUpdateMediaItems(items)
                    navController.navigate("media_viewer/$index")
                },
                onOpenDrawer = onOpenDrawer
            )
        }
        composable(Screen.Tags.route) {
            TagsScreen(
                onTagClick = { tag -> navController.navigate("tag_gallery/${tag.id}") },
                onOpenDrawer = onOpenDrawer
            )
        }
        composable(
            route           = "folder_detail/{albumId}/{albumName}",
            arguments       = listOf(
                navArgument("albumId")   { type = NavType.LongType },
                navArgument("albumName") { type = NavType.StringType }
            ),
            enterTransition = { detailForwardEnter(this) },
            exitTransition  = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition  = { detailBackExit(this) }
        ) { backStackEntry ->
            val albumId   = backStackEntry.arguments?.getLong("albumId") ?: 0L
            val albumName = backStackEntry.arguments?.getString("albumName") ?: ""
            FolderDetailScreen(
                albumId      = albumId,
                albumName    = albumName,
                showHidden   = showHidden,
                onBackClick  = { navController.popBackStack() },
                onMediaClick = { items, index ->
                    onUpdateMediaItems(items)
                    navController.navigate("media_viewer/$index")
                }
            )
        }
        composable(
            route           = Screen.TagGallery.route,
            arguments       = listOf(navArgument("tagId") { type = NavType.LongType }),
            enterTransition = { detailForwardEnter(this) },
            exitTransition  = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition  = { detailBackExit(this) }
        ) { backStackEntry ->
            val tagId = backStackEntry.arguments?.getLong("tagId") ?: 0L
            TagGalleryScreen(
                tagId        = tagId,
                showHidden   = showHidden,
                onBackClick  = { navController.popBackStack() },
                onMediaClick = { items, index ->
                    onUpdateMediaItems(items)
                    navController.navigate("media_viewer/$index")
                }
            )
        }
        composable(
            route           = "media_viewer/{index}",
            arguments       = listOf(navArgument("index") { type = NavType.IntType }),
            enterTransition = { modalScreenEnter() },
            exitTransition  = { modalScreenExit() },
            popEnterTransition = { modalScreenEnter() },
            popExitTransition  = { modalScreenExit() }
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            MediaViewer(
                items = activeMediaItems,
                initialIndex = index,
                onBackClick = { navController.popBackStack() },
                onNavigateToTag = { tagId -> navController.navigate("tag_gallery/$tagId") }
            )
        }
        composable(
            Screen.Settings.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { SettingsScreen(navController) }
        composable(
            Screen.LibrarySettings.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { LibrarySettingsScreen(navController) }
        composable(
            Screen.TaggingAISettings.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { TaggingAISettingsScreen(navController) }
        composable(
            Screen.DisplaySettings.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { DisplaySettingsScreen(navController) }
        composable(
            Screen.PerformanceSettings.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { PerformanceSettingsScreen(navController) }
        composable(
            Screen.PrivacySettings.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { PrivacySettingsScreen(navController) }
        composable(
            Screen.BackupDataSettings.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { BackupDataSettingsScreen(navController) }
        composable(
            Screen.AboutSettings.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { AboutSettingsScreen(navController) }
    }
}

@Composable
fun EmptyScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment  = Alignment.Center) {
        Text(text = title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
    }
}
