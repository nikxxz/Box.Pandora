package com.example.boxpandora.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val label: String, val icon: ImageVector) {
    object Folders : Screen("folders", "Folders", "FOLDERS", Icons.Default.Home)
    object Favorites : Screen("favorites", "Favorites", "FAVORITES", Icons.Default.Favorite)
    object Tags : Screen("tags", "Tags", "TAGS", Icons.AutoMirrored.Filled.List)
}

val bottomNavItems = listOf(
    Screen.Folders,
    Screen.Favorites,
    Screen.Tags
)
