package com.example.boxpandora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
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
    SettingsListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)
            )
        }
    )
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = boxPandoraModalTokens()
    SettingsListRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.graphicsLayer(scaleX = 0.78f, scaleY = 0.78f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.selectedAccent,
                    checkedTrackColor = tokens.selectedAccent.copy(alpha = 0.28f),
                    uncheckedThumbColor = tokens.iconBackgroundNeutral,
                    uncheckedTrackColor = tokens.rowPressedBackground
                )
            )
        }
    )
}

@Composable
fun ValueSelectorRow(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    SettingsListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.9f))
            }
        }
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
    SettingsListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = onClick
    )
}

@Composable
fun SettingSectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 10.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color? = null,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val tokens = boxPandoraModalTokens()

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                icon?.let {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(tokens.iconBackgroundNeutral, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = iconColor ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                    }
                }

                trailing?.invoke()
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(start = if (icon != null) 74.dp else 20.dp, end = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        )
    }
}
