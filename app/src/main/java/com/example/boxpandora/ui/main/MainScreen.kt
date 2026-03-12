package com.example.boxpandora.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

private const val NAV_FADE_MS  = 300
private const val NAV_SLIDE_MS = 400
private const val DRAWER_OVERLAY_MS = 150
private const val DRAWER_PANEL_MS   = 200

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
                        enter = slideInVertically(animationSpec = tween(200)) { it / 3 } + fadeIn(animationSpec = tween(200)),
                        exit  = slideOutVertically(animationSpec = tween(200)) { it / 3 } + fadeOut(animationSpec = tween(200))
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
                enter   = fadeIn(tween(DRAWER_OVERLAY_MS, easing = FastOutSlowInEasing)),
                exit    = fadeOut(tween(DRAWER_OVERLAY_MS, easing = FastOutSlowInEasing))
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
                enter   = slideInHorizontally(tween(DRAWER_PANEL_MS, easing = FastOutSlowInEasing)) { -it },
                exit    = slideOutHorizontally(tween(DRAWER_PANEL_MS, easing = FastOutSlowInEasing)) { -it }
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
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
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
                    .width(32.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Quick Options",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
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

            Spacer(Modifier.height(8.dp))

            SegmentedSelector(
                label = "Grid Size",
                options = listOf(2, 3, 4, 5),
                selected = gridSize,
                onSelected = onGridSizeSet
            ) { it.toString() }

            Spacer(Modifier.height(24.dp))

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
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
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
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        val tokens = boxPandoraModalTokens()
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
        composable(Screen.Favorites.route) { EmptyScreen("Favorites") }
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
            enterTransition = { slideIntoContainer(towards = AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(NAV_SLIDE_MS, easing = EaseIn)) + fadeIn(tween(NAV_SLIDE_MS)) },
            exitTransition  = { fadeOut(tween(NAV_FADE_MS)) },
            popEnterTransition = { fadeIn(tween(NAV_FADE_MS)) },
            popExitTransition  = { slideOutOfContainer(towards = AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(NAV_SLIDE_MS, easing = EaseOut)) + fadeOut(tween(NAV_SLIDE_MS)) }
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
            enterTransition = { slideIntoContainer(towards = AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(NAV_SLIDE_MS, easing = EaseIn)) + fadeIn(tween(NAV_SLIDE_MS)) },
            exitTransition  = { fadeOut(tween(NAV_FADE_MS)) },
            popEnterTransition = { fadeIn(tween(NAV_FADE_MS)) },
            popExitTransition  = { slideOutOfContainer(towards = AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(NAV_SLIDE_MS, easing = EaseOut)) + fadeOut(tween(NAV_SLIDE_MS)) }
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
            enterTransition = { fadeIn(tween(NAV_SLIDE_MS)) + scaleIn(initialScale = 0.92f, animationSpec = tween(NAV_SLIDE_MS)) },
            exitTransition  = { fadeOut(tween(NAV_FADE_MS)) + scaleOut(targetScale = 0.92f, animationSpec = tween(NAV_FADE_MS)) }
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            MediaViewer(
                items = activeMediaItems,
                initialIndex = index,
                onBackClick = { navController.popBackStack() },
                onNavigateToTag = { tagId -> navController.navigate("tag_gallery/$tagId") }
            )
        }
        composable(Screen.Settings.route) { SettingsScreen(navController) }
        composable(Screen.LibrarySettings.route) { LibrarySettingsScreen(navController) }
        composable(Screen.TaggingAISettings.route) { TaggingAISettingsScreen(navController) }
        composable(Screen.DisplaySettings.route) { DisplaySettingsScreen(navController) }
        composable(Screen.PerformanceSettings.route) { PerformanceSettingsScreen(navController) }
        composable(Screen.PrivacySettings.route) { PrivacySettingsScreen(navController) }
        composable(Screen.BackupDataSettings.route) { BackupDataSettingsScreen(navController) }
        composable(Screen.AboutSettings.route) { AboutSettingsScreen(navController) }
    }
}

@Composable
fun EmptyScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment  = Alignment.Center) {
        Text(text = title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
    }
}
