package com.example.boxpandora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

@Composable
fun SettingsNavigationRow(screen: Screen, navController: NavController) {
    NavigationRow(
        title = screen.title,
        subtitle = getScreenSubtitle(screen),
        icon = screen.icon,
        onClick = { navController.navigate(screen.route) }
    )
}

fun getScreenSubtitle(screen: Screen): String? = when(screen) {
    Screen.LibrarySettings -> "8 folders scanned • Hidden folders off"
    Screen.TaggingAISettings -> "Auto-tag on • Merge similar tags on"
    Screen.DisplaySettings -> "Dark mode • Medium grid"
    Screen.PerformanceSettings -> "Cache 128 MB • Database healthy"
    Screen.PrivacySettings -> "App lock off • 3 sensitive tags hidden"
    Screen.BackupDataSettings -> "Last backup 3 days ago"
    Screen.AboutSettings -> "Version 1.0 • Diagnostics available"
    else -> null
}

fun getScreenKeywords(screen: Screen): List<String> = when(screen) {
    Screen.LibrarySettings -> listOf("folders", "scan", "paths", "hidden", "nomedia", "exclude")
    Screen.TaggingAISettings -> listOf("ai", "tags", "auto", "confidence", "synonyms", "categories")
    Screen.DisplaySettings -> listOf("theme", "dark", "light", "grid", "columns", "sort", "accent", "animations")
    Screen.PerformanceSettings -> listOf("cache", "database", "optimize", "clean", "thumbnail", "maintenance")
    Screen.PrivacySettings -> listOf("lock", "pin", "fingerprint", "private", "sensitive", "hidden", "protection")
    Screen.BackupDataSettings -> listOf("backup", "export", "import", "json", "restore", "cloud")
    Screen.AboutSettings -> listOf("version", "stats", "license", "debug", "logs", "support")
    else -> emptyList()
}

@Composable
fun NavigationRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        leadingContent = icon?.let { { 
            Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                Icon(
                    imageVector = it, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                ) 
            }
        } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
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
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = { 
            Switch(
                checked = checked, 
                onCheckedChange = onCheckedChange,
                modifier = Modifier.graphicsLayer(scaleX = 0.8f, scaleY = 0.8f)
            ) 
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
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
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun ActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        leadingContent = icon?.let { { 
            Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                Icon(
                    imageVector = it, 
                    contentDescription = null, 
                    tint = iconColor ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                ) 
            }
        } },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun SettingSectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.primary
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
