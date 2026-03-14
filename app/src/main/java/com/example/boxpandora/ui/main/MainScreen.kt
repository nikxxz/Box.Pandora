package com.example.boxpandora.ui.main

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.data.local.entity.MediaItem
import com.example.boxpandora.ui.components.media.MediaViewer
import com.example.boxpandora.ui.common.AppLockGateDialog
import com.example.boxpandora.ui.common.AppDialog
import com.example.boxpandora.ui.common.ModalHeader
import com.example.boxpandora.ui.common.createDeviceCredentialIntent
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
    val activity = LocalContext.current as ComponentActivity
    val context = activity
    val app = context.applicationContext as PandoraApp
    val themeViewModel: ThemeViewModel = viewModel(
        factory = ThemeViewModelFactory(app.database.userPreferenceDao())
    )
    val appLockViewModel: AppLockViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = AppLockViewModelFactory(app.database.userPreferenceDao())
    )
    val maintenanceViewModel: MaintenanceViewModel = viewModel(
        factory = MaintenanceViewModelFactory(app.repository)
    )
    val foldersViewModel: FoldersViewModel = viewModel(
        factory = FoldersViewModelFactory(app.repository)
    )
    val homeTab by foldersViewModel.currentTab.collectAsState()

    val themeMode    by themeViewModel.themeMode.collectAsState()
    val showHidden   by themeViewModel.showHidden.collectAsState()
    val sortOrder    by themeViewModel.sortOrder.collectAsState()
    val showGradient by themeViewModel.showGradient.collectAsState()
    val accentColor  by themeViewModel.accentColor.collectAsState()
    val appLockSettings by appLockViewModel.settings.collectAsState()
    val isAppLocked by appLockViewModel.isLocked.collectAsState()
    val appLockError by appLockViewModel.unlockError.collectAsState()

    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var activeMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isDrawerOpen by remember { mutableStateOf(false) }

    val isProcessing by maintenanceViewModel.isProcessing.collectAsState()
    val status by maintenanceViewModel.status.collectAsState()
    val progress by maintenanceViewModel.progress.collectAsState()

    val deviceCredentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            appLockViewModel.unlockSuccess()
        }
    }

    if (isDrawerOpen && !isAppLocked) {
        BackHandler { isDrawerOpen = false }
    }

    if (isAppLocked) {
        BackHandler {}
    }

    DisposableEffect(lifecycleOwner, activity, appLockSettings.isEnabled, appLockSettings.timeout) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!activity.isChangingConfigurations) {
                        appLockViewModel.onAppBackgrounded()
                    }
                }
                Lifecycle.Event.ON_START -> appLockViewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(isAppLocked) {
        if (isAppLocked) {
            isDrawerOpen = false
        }
    }

    LaunchedEffect(isAppLocked, appLockSettings.mode) {
        if (isAppLocked && appLockSettings.mode == AppLockMode.DEVICE_CREDENTIAL) {
            createDeviceCredentialIntent(
                context = activity,
                title = "Unlock Pandora",
                description = "Use your phone lock to continue."
            )?.let(deviceCredentialLauncher::launch)
        }
    }

    BoxPandoraTheme(themeMode = themeMode, showGradient = showGradient, accentColor = accentColor) {
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
                                        val navLabelText = if (screen == Screen.Folders) {
                                            if (homeTab == HomeTab.ALL_MEDIA) "MEDIA" else "FOLDERS"
                                        } else {
                                            screen.label
                                        }
                                        AnimatedContent(
                                            targetState = navLabelText,
                                            transitionSpec = {
                                                val toMedia = targetState == "MEDIA"
                                                (slideInHorizontally(animationSpec = tween(PandoraMotion.Standard)) { if (toMedia) it else -it } +
                                                    fadeIn(animationSpec = tween(PandoraMotion.Standard))).togetherWith(
                                                    slideOutHorizontally(animationSpec = tween(PandoraMotion.Standard)) { if (toMedia) -it else it } +
                                                        fadeOut(animationSpec = tween(PandoraMotion.Quick))
                                                )
                                            },
                                            label = "navLabel"
                                        ) { displayText ->
                                            Text(
                                                text = displayText,
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
                                        }
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
                        showHidden         = showHidden,
                        foldersViewModel   = foldersViewModel
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
                    themeMode          = themeMode,
                    showHidden         = showHidden,
                    sortOrder          = sortOrder,
                    showGradient       = showGradient,
                    accentColor        = accentColor,
                    onThemeSet         = { themeViewModel.setThemeMode(it) },
                    onToggleHide       = { themeViewModel.setShowHidden(!showHidden) },
                    onSortOrderSet     = { themeViewModel.setSortOrder(it) },
                    onToggleGradient   = { themeViewModel.setShowGradient(!showGradient) },
                    onAccentColorSet   = { themeViewModel.setAccentColor(it) },
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

            if (appLockSettings.isEnabled && isAppLocked) {
                AppLockGateDialog(
                    mode = appLockSettings.mode,
                    errorMessage = appLockError,
                    onUnlockWithPin = { pin -> appLockViewModel.unlockWithPin(pin) },
                    onUnlockWithPhoneLock = {
                        createDeviceCredentialIntent(
                            context = activity,
                            title = "Unlock Pandora",
                            description = "Use your phone lock to continue."
                        )?.let(deviceCredentialLauncher::launch)
                    },
                    onClearError = { appLockViewModel.clearUnlockError() }
                )
            }
        }
    }
}

@Composable
fun SettingsDrawer(
    themeMode: ThemeMode,
    showHidden: Boolean,
    sortOrder: SortOrder,
    showGradient: Boolean,
    accentColor: AccentColor,
    onThemeSet: (ThemeMode) -> Unit,
    onToggleHide: () -> Unit,
    onSortOrderSet: (SortOrder) -> Unit,
    onToggleGradient: () -> Unit,
    onAccentColorSet: (AccentColor) -> Unit,
    onOpenFullSettings: () -> Unit
) {
    val tokens = boxPandoraModalTokens()
    var showAccentPicker by remember { mutableStateOf(false) }

    if (showAccentPicker) {
        AppDialog(onDismiss = { showAccentPicker = false }) {
            ModalHeader(
                title = "Accent Color",
                subtitle = "Choose the primary accent used across the interface."
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AccentColor.values().toList().chunked(5).forEach { rowColors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        rowColors.forEach { c ->
                            val swatchColor = Color(c.colorLong)
                            val isSelected = accentColor == c
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .then(
                                        if (isSelected)
                                            Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), CircleShape)
                                        else Modifier
                                    )
                                    .clickable {
                                        onAccentColorSet(c)
                                        showAccentPicker = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (swatchColor.luminance() > 0.5f) Color(0xFF333333) else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = accentColor.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = Color(accentColor.colorLong)
                )
            }
            Button(
                onClick = { showAccentPicker = false },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.iconBackgroundNeutral,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
            ) {
                Text("Close")
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(308.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp),
                color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) tokens.cardBackground.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, tokens.border),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = tokens.cardBackground.copy(alpha = 0.86f),
                        border = BorderStroke(1.dp, tokens.border),
                        tonalElevation = 0.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(42.dp)
                                    .height(4.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                                        shape = RoundedCornerShape(999.dp)
                                    )
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "pandora",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.W200,
                                    letterSpacing = 4.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Display and library controls framed with the same monochrome restraint as the media viewer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
                            )
                        }
                    }

                    DrawerSectionCard(title = "Appearance") {
                        SegmentedSelector(
                            label = "Theme Mode",
                            options = listOf(ThemeMode.AUTO, ThemeMode.LIGHT, ThemeMode.DARK),
                            selected = themeMode,
                            onSelected = onThemeSet
                        ) { mode ->
                            when (mode) {
                                ThemeMode.AUTO  -> "Auto"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK  -> "Dark"
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            onClick = { showAccentPicker = true },
                            shape = RoundedCornerShape(20.dp),
                            color = tokens.iconBackgroundNeutral,
                            border = BorderStroke(1.dp, tokens.border),
                            tonalElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(Color(accentColor.colorLong))
                                    )
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "ACCENT COLOR",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            letterSpacing = 1.1.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                    )
                                    Text(
                                        text = accentColor.displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        CompactToggleRow(
                            title = "Subtle background gradient",
                            checked = showGradient,
                            onCheckedChange = { onToggleGradient() }
                        )
                    }

                    DrawerSectionCard(title = "Content") {
                        CompactToggleRow(
                            title = "Show hidden files",
                            checked = showHidden,
                            onCheckedChange = { onToggleHide() }
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Sort by",
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.1.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Spacer(Modifier.height(10.dp))
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
                                        selectedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                                        containerColor = tokens.iconBackgroundNeutral,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                                        selectedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                    )
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = onOpenFullSettings,
                        shape = RoundedCornerShape(24.dp),
                        color = tokens.cardBackground.copy(alpha = 0.82f),
                        border = BorderStroke(1.dp, tokens.border),
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Settings",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Open the full control surface",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = boxPandoraModalTokens()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = tokens.cardBackground.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, tokens.border),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            QuickSectionHeader(title)
            content()
        }
    }
}

@Composable
fun QuickSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
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
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
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
    showDivider: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = boxPandoraModalTokens()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 20.dp, vertical = 14.dp),
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
        if (showDivider) {
            HorizontalDivider(color = tokens.divider)
        }
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
    showHidden: Boolean,
    foldersViewModel: FoldersViewModel
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
                onOpenDrawer  = onOpenDrawer,
                viewModel     = foldersViewModel
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
            Screen.LibraryIncludedDirs.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { IncludedDirectoriesScreen(navController) }
        composable(
            Screen.LibraryExcludedFolders.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { ExcludedFoldersScreen(navController) }
        composable(
            Screen.LibraryFilterTypes.route,
            enterTransition = { detailForwardEnter(this) },
            exitTransition = { detailForwardExit() },
            popEnterTransition = { detailBackEnter() },
            popExitTransition = { detailBackExit(this) }
        ) { FilterMediaTypesScreen(navController) }
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
