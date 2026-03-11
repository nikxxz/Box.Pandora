package com.example.boxpandora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            item { NavigationRow(Screen.LibrarySettings, navController) }
            item { NavigationRow(Screen.TaggingAISettings, navController) }
            item { NavigationRow(Screen.DisplaySettings, navController) }
            item { NavigationRow(Screen.PerformanceSettings, navController) }
            item { NavigationRow(Screen.PrivacySettings, navController) }
            item { NavigationRow(Screen.BackupDataSettings, navController) }
            item { NavigationRow(Screen.AboutSettings, navController) }
        }
    }
}

@Composable
fun NavigationRow(screen: Screen, navController: NavController) {
    NavigationRow(
        title = screen.title,
        subtitle = getScreenSubtitle(screen),
        icon = screen.icon,
        onClick = { navController.navigate(screen.route) }
    )
}

private fun getScreenSubtitle(screen: Screen): String? = when(screen) {
    Screen.LibrarySettings -> "Scanning paths and file types"
    Screen.TaggingAISettings -> "Auto-tagging and content analysis"
    Screen.DisplaySettings -> "Grid layout and visual theme"
    Screen.PerformanceSettings -> "Cache and database health"
    Screen.PrivacySettings -> "Biometric lock and hidden content"
    Screen.BackupDataSettings -> "Export tags and database backups"
    Screen.AboutSettings -> "App version and storage statistics"
    else -> null
}

@Composable
fun NavigationRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = { 
            Switch(
                checked = checked, 
                onCheckedChange = onCheckedChange,
                modifier = Modifier.graphicsLayer(scaleX = 0.8f, scaleY = 0.8f)
            ) 
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
fun ValueSelectorRow(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun ActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = iconColor) } },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun SettingSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
    )
}
