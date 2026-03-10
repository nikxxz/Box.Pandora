package com.example.boxpandora.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.common.AppHeader
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.ui.main.viewmodel.ThemeMode
import com.example.boxpandora.ui.main.viewmodel.ThemeViewModel
import com.example.boxpandora.ui.main.viewmodel.ThemeViewModelFactory
import com.example.boxpandora.ui.theme.BoxPandoraTheme
import kotlinx.coroutines.launch

private const val NAV_FADE_MS = 300
private const val NAV_SLIDE_MS = 400

@Composable
fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as PandoraApp
    val themeViewModel: ThemeViewModel = viewModel(
        factory = ThemeViewModelFactory(app.database.userPreferenceDao())
    )
    val themeMode by themeViewModel.themeMode.collectAsState()
    val showHidden by themeViewModel.showHidden.collectAsState()
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    var activeMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    val gesturesEnabled = currentRoute != "media_viewer/{index}"

    BoxPandoraTheme(themeMode = themeMode) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
                            
                            Text(
                                "THEME",
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                ThemeButton(
                                    icon = Icons.Default.Brightness7,
                                    label = "LIGHT",
                                    selected = themeMode == ThemeMode.LIGHT,
                                    onClick = { themeViewModel.setThemeMode(ThemeMode.LIGHT) }
                                )
                                ThemeButton(
                                    icon = Icons.Default.Brightness4,
                                    label = "DARK",
                                    selected = themeMode == ThemeMode.DARK,
                                    onClick = { themeViewModel.setThemeMode(ThemeMode.DARK) }
                                )
                                ThemeButton(
                                    icon = Icons.Default.BrightnessAuto,
                                    label = "AUTO",
                                    selected = themeMode == ThemeMode.AUTO,
                                    onClick = { themeViewModel.setThemeMode(ThemeMode.AUTO) }
                                )
                            }

                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                            NavigationDrawerItem(
                                label = { Text(if (showHidden) "HIDE HIDDEN" else "SHOW HIDDEN") },
                                icon = { Icon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null) },
                                selected = false,
                                onClick = { 
                                    themeViewModel.setShowHidden(!showHidden)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )

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
                    }
                },
                gesturesEnabled = gesturesEnabled
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Scaffold(
                        bottomBar = {
                            AnimatedVisibility(
                                visible = currentRoute in bottomNavItems.map { it.route },
                                enter = slideInVertically { it } + fadeIn(),
                                exit = slideOutVertically { it } + fadeOut()
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
                                .padding(bottom = if (currentRoute in bottomNavItems.map { it.route }) innerPadding.calculateBottomPadding() else 0.dp)
                        ) {
                            NavigationGraph(
                                navController = navController,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                activeMediaItems = activeMediaItems,
                                onUpdateMediaItems = { activeMediaItems = it },
                                showHidden = showHidden
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(80.dp)
            .height(64.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
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
    NavHost(
        navController = navController,
        startDestination = Screen.Folders.route,
        // Default transitions for tab switching
        enterTransition = { fadeIn(tween(NAV_FADE_MS, easing = EaseIn)) },
        exitTransition  = { fadeOut(tween(NAV_FADE_MS, easing = EaseOut)) },
        popEnterTransition = { fadeIn(tween(NAV_FADE_MS, easing = EaseIn)) },
        popExitTransition  = { fadeOut(tween(NAV_FADE_MS, easing = EaseOut)) }
    ) {
        composable(Screen.Folders.route) {
            Column {
                AppHeader(onMenuClick = onOpenDrawer, onSearchClick = { })
                FoldersScreen(
                    showHidden = showHidden,
                    onFolderClick = { album ->
                        navController.navigate("folder_detail/${album.name}")
                    }
                )
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
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(NAV_SLIDE_MS, easing = EaseIn)
                ) + fadeIn(tween(NAV_SLIDE_MS))
            },
            exitTransition = {
                fadeOut(tween(NAV_FADE_MS))
            },
            popEnterTransition = {
                fadeIn(tween(NAV_FADE_MS))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(NAV_SLIDE_MS, easing = EaseOut)
                ) + fadeOut(tween(NAV_SLIDE_MS))
            }
        ) { backStackEntry ->
            val albumName = backStackEntry.arguments?.getString("albumName") ?: ""
            FolderDetailScreen(
                albumName = albumName,
                showHidden = showHidden,
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
                fadeIn(tween(NAV_SLIDE_MS)) + scaleIn(initialScale = 0.92f, animationSpec = tween(NAV_SLIDE_MS))
            },
            exitTransition = {
                fadeOut(tween(NAV_FADE_MS)) + scaleOut(targetScale = 0.92f, animationSpec = tween(NAV_FADE_MS))
            },
            popEnterTransition = {
                fadeIn(tween(NAV_SLIDE_MS)) + scaleIn(initialScale = 0.92f, animationSpec = tween(NAV_SLIDE_MS))
            },
            popExitTransition = {
                fadeOut(tween(NAV_FADE_MS)) + scaleOut(targetScale = 0.92f, animationSpec = tween(NAV_FADE_MS))
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
