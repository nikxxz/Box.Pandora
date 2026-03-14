package com.example.boxpandora.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.navigation.NavController
import com.example.boxpandora.ui.main.Screen
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val tokens = boxPandoraModalTokens()
    val settingsItems = remember {
        listOf(
            Screen.LibrarySettings,
            Screen.TaggingAISettings,
            Screen.DisplaySettings,
            Screen.PerformanceSettings,
            Screen.PrivacySettings,
            Screen.BackupDataSettings,
            Screen.AboutSettings
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp, top = 10.dp)
        ) {
            item {
                 {
                   
                }
            }

            item {
                SettingSectionHeader(
                    title = "Controls",
                    subtitle = "Core library, styling, performance, and support"
                )
            }

            items(settingsItems) { screen ->
                SettingsNavigationRow(screen, navController)
            }
        }
    }
}

@Composable
private fun SettingsMetaChip(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        tonalElevation = 0.dp
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
