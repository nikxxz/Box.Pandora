package com.example.boxpandora.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val label: String, val icon: ImageVector) {
    object Folders : Screen("folders", "Folders", "FOLDERS", Icons.Default.Home)
    object Favorites : Screen("favorites", "Favorites", "FAVORITES", Icons.Default.Favorite)
    object Tags : Screen("tags", "Tags", "TAGS", Icons.AutoMirrored.Filled.List)
    
    // Non-bottom-nav routes
    object TagGallery : Screen("tag_gallery/{tagId}", "Tag Gallery", "TAG GALLERY", Icons.AutoMirrored.Filled.List)

    // Settings Screens
    object Settings : Screen("settings", "Settings", "SETTINGS", Icons.Default.Settings)
    object LibrarySettings : Screen("settings/library", "Library", "LIBRARY", Icons.Default.LibraryMusic)
    object TaggingAISettings : Screen("settings/tagging_ai", "Tagging & AI", "TAGGING & AI", Icons.Default.AutoAwesome)
    object DisplaySettings : Screen("settings/display", "Display", "DISPLAY", Icons.Default.DisplaySettings)
    object PerformanceSettings : Screen("settings/performance", "Performance", "PERFORMANCE", Icons.Default.Speed)
    object PrivacySettings : Screen("settings/privacy", "Privacy", "PRIVACY", Icons.Default.Lock)
    object BackupDataSettings : Screen("settings/backup_data", "Backup & Data", "BACKUP & DATA", Icons.Default.Storage)
    object AboutSettings : Screen("settings/about", "About", "ABOUT", Icons.Default.Info)
}

val bottomNavItems = listOf(
    Screen.Folders,
    Screen.Favorites,
    Screen.Tags
)
