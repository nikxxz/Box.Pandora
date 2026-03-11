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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.boxpandora.ui.main.viewmodel.MaintenanceViewModel
import com.example.boxpandora.ui.main.viewmodel.MaintenanceViewModelFactory
import com.example.boxpandora.ui.main.viewmodel.ThemeMode
import com.example.boxpandora.ui.main.viewmodel.ThemeViewModel
import com.example.boxpandora.ui.main.viewmodel.ThemeViewModelFactory
import com.example.boxpandora.ui.settings.*
import com.example.boxpandora.ui.theme.BoxPandoraTheme

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

    val themeMode  by themeViewModel.themeMode.collectAsState()
    val showHidden by themeViewModel.showHidden.collectAsState()

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

    BoxPandoraTheme(themeMode = themeMode) {
        Box(modifier = Modifier.fillMaxSize()) {

            Scaffold(
                bottomBar = {
                    AnimatedVisibility(
                        visible = bottomNavItems.any { it.route == currentRoute },
                        enter = slideInVertically(animationSpec = tween(200)) { it / 3 } + fadeIn(animationSpec = tween(200)),
                        exit  = slideOutVertically(animationSpec = tween(200)) { it / 3 } + fadeOut(animationSpec = tween(200))
                    ) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.background,
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
                                        indicatorColor = MaterialTheme.colorScheme.background
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
                        .background(Color.Black.copy(0.4f))
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
                    onThemeSet     = { themeViewModel.setThemeMode(it) },
                    onToggleHide   = { themeViewModel.setShowHidden(!showHidden) },
                    onReindex      = { maintenanceViewModel.reindex() },
                    onForceRecheck = { maintenanceViewModel.forceRecheck() },
                    onOpenFullSettings = {
                        isDrawerOpen = false
                        navController.navigate(Screen.Settings.route)
                    },
                    onClose        = { isDrawerOpen = false }
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
fun SyncProgressModal(
    status: String,
    progress: Float,
    isProcessing: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text("Media Library Sync") },
        text = {
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
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    // Error state or indeterminate
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        },
        confirmButton = {
            if (!isProcessing) {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    )
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
            EmptyScreen("Favorites")
        }

        composable(Screen.Tags.route) {
            TagsScreen(
                onTagClick = { tag ->
                    navController.navigate("tag_gallery/${tag.id}")
                },
                onOpenDrawer = onOpenDrawer
            )
        }

        composable(
            route           = "folder_detail/{albumId}/{albumName}",
            arguments       = listOf(
                navArgument("albumId")   { type = NavType.LongType },
                navArgument("albumName") { type = NavType.StringType }
            ),
            enterTransition = {
                slideIntoContainer(
                    towards       = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(NAV_SLIDE_MS, easing = EaseIn)
                ) + fadeIn(tween(NAV_SLIDE_MS))
            },
            exitTransition  = { fadeOut(tween(NAV_FADE_MS)) },
            popEnterTransition = { fadeIn(tween(NAV_FADE_MS)) },
            popExitTransition  = {
                slideOutOfContainer(
                    towards       = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(NAV_SLIDE_MS, easing = EaseOut)
                ) + fadeOut(tween(NAV_SLIDE_MS))
            }
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
            arguments       = listOf(
                navArgument("tagId") { type = NavType.LongType }
            ),
            enterTransition = {
                slideIntoContainer(
                    towards       = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(NAV_SLIDE_MS, easing = EaseIn)
                ) + fadeIn(tween(NAV_SLIDE_MS))
            },
            exitTransition  = { fadeOut(tween(NAV_FADE_MS)) },
            popEnterTransition = { fadeIn(tween(NAV_FADE_MS)) },
            popExitTransition  = {
                slideOutOfContainer(
                    towards       = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(NAV_SLIDE_MS, easing = EaseOut)
                ) + fadeOut(tween(NAV_SLIDE_MS))
            }
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
            enterTransition = {
                fadeIn(tween(NAV_SLIDE_MS)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(NAV_SLIDE_MS))
            },
            exitTransition  = {
                fadeOut(tween(NAV_FADE_MS)) +
                scaleOut(targetScale = 0.92f, animationSpec = tween(NAV_FADE_MS))
            },
            popEnterTransition = {
                fadeIn(tween(NAV_SLIDE_MS)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(NAV_SLIDE_MS))
            },
            popExitTransition  = {
                fadeOut(tween(NAV_FADE_MS)) +
                scaleOut(targetScale = 0.92f, animationSpec = tween(NAV_FADE_MS))
            }
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            MediaViewer(
                items        = activeMediaItems,
                initialIndex = index,
                onBackClick  = { navController.popBackStack() }
            )
        }

        // Settings Routes
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
fun SettingsDrawer(
    themeMode: ThemeMode,
    showHidden: Boolean,
    onThemeSet: (ThemeMode) -> Unit,
    onToggleHide: () -> Unit,
    onReindex: () -> Unit,
    onForceRecheck: () -> Unit,
    onOpenFullSettings: () -> Unit,
    onClose: () -> Unit
) {
    val isAuto = themeMode == ThemeMode.AUTO

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(24.dp)
    ) {
        Text(
            text = "Options",
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "User Interface colors",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Automatic Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onThemeSet(ThemeMode.AUTO) }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isAuto,
                        onClick = { onThemeSet(ThemeMode.AUTO) }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Automatic",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Colors will change according to day time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                // Manual Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { if (isAuto) onThemeSet(ThemeMode.LIGHT) }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !isAuto,
                        onClick = { if (isAuto) onThemeSet(ThemeMode.LIGHT) }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Manual",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Change colors yourself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                // Light/Dark Switch (Only if Manual)
                AnimatedVisibility(
                    visible = !isAuto,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = 48.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            "Light",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (themeMode == ThemeMode.LIGHT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = themeMode == ThemeMode.DARK,
                            onCheckedChange = { isDark ->
                                onThemeSet(if (isDark) ThemeMode.DARK else ThemeMode.LIGHT)
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Dark",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (themeMode == ThemeMode.DARK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Content visibility",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onToggleHide() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Show Hidden Files",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Switch(checked = showHidden, onCheckedChange = { onToggleHide() })
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Application",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenFullSettings() }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Full Settings",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Configure library, AI, and performance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun EmptyScreen(title: String) {
    Box(
        modifier          = Modifier.fillMaxSize(),
        contentAlignment  = Alignment.Center
    ) {
        Text(
            text  = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}
