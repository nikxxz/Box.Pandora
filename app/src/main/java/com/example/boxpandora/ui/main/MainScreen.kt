package com.example.boxpandora.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.common.AppHeader
import kotlinx.coroutines.launch

private const val NAV_FADE_MS = 250
private const val NAV_SLIDE_MS = 300

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // Shared state for the viewer
    var activeMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "PANDORA",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                NavigationDrawerItem(
                    label = { Text("SETTINGS") },
                    selected = false,
                    onClick = { },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("ABOUT") },
                    selected = false,
                    onClick = { },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        },
        gesturesEnabled = true
    ) {
        Scaffold(
            bottomBar = {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                if (currentRoute in bottomNavItems.map { it.route }) {
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
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
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
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                NavigationGraph(
                    navController = navController,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    activeMediaItems = activeMediaItems,
                    onUpdateMediaItems = { activeMediaItems = it }
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
    onUpdateMediaItems: (List<MediaItem>) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Folders.route,
        enterTransition = { fadeIn(tween(NAV_FADE_MS)) },
        exitTransition  = { fadeOut(tween(NAV_FADE_MS)) },
        popEnterTransition  = { fadeIn(tween(NAV_FADE_MS)) },
        popExitTransition   = { fadeOut(tween(NAV_FADE_MS)) }
    ) {
        composable(Screen.Folders.route) {
            Column {
                AppHeader(onMenuClick = onOpenDrawer, onSearchClick = { })
                FoldersScreen(onFolderClick = { album ->
                    navController.navigate("folder_detail/${album.name}")
                })
            }
        }
        composable(Screen.Favorites.route) {
            Column {
                AppHeader(onMenuClick = onOpenDrawer, onSearchClick = { })
                EmptyScreen(title = "Favorites")
            }
        }
        composable(Screen.Tags.route) {
            Column {
                AppHeader(onMenuClick = onOpenDrawer, onSearchClick = { })
                EmptyScreen(title = "Tags")
            }
        }

        composable(
            route = "folder_detail/{albumName}",
            enterTransition = {
                slideInHorizontally(tween(NAV_SLIDE_MS)) { it } + fadeIn(tween(NAV_SLIDE_MS))
            },
            exitTransition = {
                fadeOut(tween(NAV_FADE_MS))
            },
            popEnterTransition = {
                fadeIn(tween(NAV_FADE_MS))
            },
            popExitTransition = {
                slideOutHorizontally(tween(NAV_SLIDE_MS)) { it } + fadeOut(tween(NAV_SLIDE_MS))
            }
        ) { backStackEntry ->
            val albumName = backStackEntry.arguments?.getString("albumName") ?: ""
            FolderDetailScreen(
                albumName = albumName,
                onBackClick = { navController.popBackStack() },
                onMediaClick = { items, index ->
                    onUpdateMediaItems(items)
                    navController.navigate("media_viewer/$index")
                }
            )
        }

        composable(
            route = "media_viewer/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType }),
            enterTransition = {
                scaleIn(tween(NAV_SLIDE_MS), initialScale = 0.9f) + fadeIn(tween(NAV_SLIDE_MS))
            },
            exitTransition = {
                scaleOut(tween(NAV_SLIDE_MS), targetScale = 0.9f) + fadeOut(tween(NAV_SLIDE_MS))
            },
            popEnterTransition = {
                scaleIn(tween(NAV_SLIDE_MS), initialScale = 0.9f) + fadeIn(tween(NAV_SLIDE_MS))
            },
            popExitTransition = {
                scaleOut(tween(NAV_SLIDE_MS), targetScale = 0.9f) + fadeOut(tween(NAV_SLIDE_MS))
            }
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            MediaViewerScreen(
                items = activeMediaItems,
                initialIndex = index,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun EmptyScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.ExtraLight,
                letterSpacing = 4.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
        )
    }
}
