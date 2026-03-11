package com.example.boxpandora.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubScreen(
    title: String,
    navController: NavController,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            content()
        }
    }
}

@Composable
fun LibrarySettingsScreen(navController: NavController) {
    SettingsSubScreen("Library Settings", navController) {
        LazyColumn {
            item { SettingSectionHeader("Media behaviour") }
            item { SettingItem("Include/Exclude directories", "Choose which folders to scan", Icons.Default.Folder) }
            item { SettingItem("Re-scan & Recache Media", "Manually trigger a full library refresh", Icons.Default.Refresh) }
            item { SettingItem("Hidden folders", "Manage list of ignored directories", Icons.Default.VisibilityOff) }
            item { 
                var checked by remember { mutableStateOf(false) }
                SettingItem(
                    "Show hidden folders", 
                    "Display folders starting with a dot", 
                    Icons.Default.Visibility,
                    trailing = { Switch(checked = checked, onCheckedChange = { checked = it }) }
                ) 
            }
            item { SettingSectionHeader("File Types") }
            item { SettingItem("Include/Exclude file types", "Images, Videos, GIFs", Icons.Default.FilterList) }
        }
    }
}

@Composable
fun TaggingAISettingsScreen(navController: NavController) {
    SettingsSubScreen("Tagging & AI", navController) {
        LazyColumn {
            item { SettingSectionHeader("AI Tagging") }
            item { SettingItem("Tag suggestion confidence", "Low / Medium / High", Icons.Default.Psychology) }
            item { SettingItem("Tag normalization", "Standardize tag formats", Icons.Default.TextFields) }
            item { 
                var checked by remember { mutableStateOf(true) }
                SettingItem("Auto merge similar tags", "Groups synonyms automatically", trailing = { Switch(checked = checked, onCheckedChange = { checked = it }) }) 
            }
            item { 
                var checked by remember { mutableStateOf(true) }
                SettingItem("Related tag discovery", "Suggest tags based on content", trailing = { Switch(checked = checked, onCheckedChange = { checked = it }) }) 
            }
            item { 
                var checked by remember { mutableStateOf(false) }
                SettingItem("Enable co-occurrence suggestions", "Suggest tags often used together", trailing = { Switch(checked = checked, onCheckedChange = { checked = it }) }) 
            }
            item { 
                var checked by remember { mutableStateOf(true) }
                SettingItem("Auto-tag on import", "Analyze new media immediately", trailing = { Switch(checked = checked, onCheckedChange = { checked = it }) }) 
            }
            
            item { SettingSectionHeader("Tag Management") }
            item { SettingItem("Manage categories", "Organize tags into groups", Icons.Default.Category) }
            item { SettingItem("Rebuild tag index", "Refresh tag database search index", Icons.Default.Build) }
            item { SettingItem("Recompute tag relationships", "Recalculate AI suggestions", Icons.Default.AutoGraph) }
        }
    }
}

@Composable
fun DisplaySettingsScreen(navController: NavController) {
    SettingsSubScreen("Display Settings", navController) {
        LazyColumn {
            item { SettingSectionHeader("Appearance") }
            item { SettingItem("Grid size", "Small / Medium / Large", Icons.Default.GridView) }
            item { 
                var checked by remember { mutableStateOf(false) }
                SettingItem("Show metadata overlay", "Overlay file info on thumbnails", trailing = { Switch(checked = checked, onCheckedChange = { checked = it }) }) 
            }
            item { SettingItem("Default sort", "Date / Name / Size", Icons.AutoMirrored.Filled.Sort) }
            
            item { SettingSectionHeader("Theme") }
            item { SettingItem("Animation speed", "UI transition speed", Icons.Default.Speed) }
            item { SettingItem("System theme", "Dark / Light / Auto", Icons.Default.Brightness4) }
            item { SettingItem("Accent Color", "Change app primary color", Icons.Default.Palette) }
        }
    }
}

@Composable
fun PerformanceSettingsScreen(navController: NavController) {
    SettingsSubScreen("Performance", navController) {
        LazyColumn {
            item { SettingSectionHeader("Maintenance") }
            item { 
                SettingItem(
                    "Clear thumbnail cache", 
                    "Removes cached previews. Images will reload slower until rebuilt.", 
                    Icons.Default.DeleteSweep
                ) 
            }
            item { SettingItem("Database optimization", "Vacuum and rebuild database indexes", Icons.Default.Storage) }
            
            item { SettingSectionHeader("Advanced") }
            item { 
                SettingItem(
                    "Smart clean-up", 
                    "Removes orphan tags, broken media references, and unused tag aliases.", 
                    Icons.Default.CleaningServices
                ) 
            }
        }
    }
}

@Composable
fun PrivacySettingsScreen(navController: NavController) {
    SettingsSubScreen("Privacy", navController) {
        LazyColumn {
            item { SettingSectionHeader("Content Protection") }
            item { SettingItem("Hide/Show sensitive Tags", "Manage tags that mark media as private", Icons.Default.NoEncryption) }
            item { SettingItem("Lock app", "Screen Lock / Custom PIN", Icons.Default.Lock) }
            item { SettingItem("Exclude folders from AI tagging", "Prevent AI from scanning specific folders", Icons.Default.PsychologyAlt) }
        }
    }
}

@Composable
fun BackupDataSettingsScreen(navController: NavController) {
    SettingsSubScreen("Backup & Data", navController) {
        LazyColumn {
            item { SettingSectionHeader("Tags") }
            item { SettingItem("Export tags", "Save tags to a file", Icons.Default.Upload) }
            item { SettingItem("Import tags", "Restore tags from a file", Icons.Default.Download) }
            
            item { SettingSectionHeader("Database") }
            item { SettingItem("Backup database", "Create a full app data backup", Icons.Default.CloudUpload) }
            item { SettingItem("Restore database", "Restore from a previous backup", Icons.Default.CloudDownload) }
        }
    }
}

@Composable
fun AboutSettingsScreen(navController: NavController) {
    SettingsSubScreen("About / Diagnostics", navController) {
        LazyColumn {
            item { SettingSectionHeader("Diagnostics") }
            item { SettingItem("Database stats", "View internal table sizes", Icons.Default.Analytics) }
            item { SettingItem("Number of media items", "1,234 items found", Icons.Default.Image) }
            item { SettingItem("Number of tags", "567 tags created", Icons.AutoMirrored.Filled.Label) }
            item { SettingItem("Cache usage", "128 MB used", Icons.Default.Dns) }
            
            item { SettingSectionHeader("Application") }
            item { SettingItem("Version", "1.0.0 (Build 42)", Icons.Default.Info) }
            item { SettingItem("Open source licenses", "Credits and legal info", Icons.Default.Description) }
            item { SettingItem("Send debug report", "Share logs with developers", Icons.Default.BugReport) }
        }
    }
}
