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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.boxpandora.ui.main.viewmodel.ThemeMode
import com.example.boxpandora.ui.main.viewmodel.ThemeViewModel
import com.example.boxpandora.ui.main.viewmodel.ThemeViewModelFactory
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
    val themeMode  by themeViewModel.themeMode.collectAsState()
    val showHidden by themeViewModel.showHidden.collectAsState()

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var activeMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isDrawerOpen by remember { mutableStateOf(false) }

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
                    themeMode    = themeMode,
                    showHidden   = showHidden,
                    onThemeSet   = { themeViewModel.setThemeMode(it) },
                    onToggleHide = { themeViewModel.setShowHidden(!showHidden) }
                )
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
            EmptyScreen("Favorites")
        }

        composable(Screen.Tags.route) {
            TagsScreen()
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
    }
}

@Composable
fun SettingsDrawer(
    themeMode: ThemeMode,
    showHidden: Boolean,
    onThemeSet: (ThemeMode) -> Unit,
    onToggleHide: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Appearance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

        val modes = listOf(
            Triple(ThemeMode.LIGHT, "Light", Icons.Default.Brightness7),
            Triple(ThemeMode.DARK,  "Dark",  Icons.Default.Brightness4),
            Triple(ThemeMode.AUTO,  "Auto",  Icons.Default.BrightnessAuto)
        )

        modes.forEach { (mode, label, icon) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onThemeSet(mode) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(label)
                }
                RadioButton(selected = themeMode == mode, onClick = { onThemeSet(mode) })
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Visibility", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleHide() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text("Show Hidden Files")
            }
            Switch(checked = showHidden, onCheckedChange = { onToggleHide() })
        }
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
