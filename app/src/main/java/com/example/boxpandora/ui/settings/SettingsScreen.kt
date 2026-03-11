package com.example.boxpandora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.boxpandora.ui.main.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item { SettingCategoryItem(Screen.LibrarySettings, navController) }
            item { SettingCategoryItem(Screen.TaggingAISettings, navController) }
            item { SettingCategoryItem(Screen.DisplaySettings, navController) }
            item { SettingCategoryItem(Screen.PerformanceSettings, navController) }
            item { SettingCategoryItem(Screen.PrivacySettings, navController) }
            item { SettingCategoryItem(Screen.BackupDataSettings, navController) }
            item { SettingCategoryItem(Screen.AboutSettings, navController) }
        }
    }
}

@Composable
fun SettingCategoryItem(screen: Screen, navController: NavController) {
    ListItem(
        headlineContent = { Text(screen.title) },
        leadingContent = { Icon(screen.icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable { navController.navigate(screen.route) }
    )
}

@Composable
fun SettingItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun SettingSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
