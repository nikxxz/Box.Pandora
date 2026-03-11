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
    SettingsSubScreen("Library Scanning", navController) {
        LazyColumn {
            item { SettingSectionHeader("Library Scanning") }
            item { NavigationRow("Included Directories", "Choose which folders to scan for media", Icons.Default.Folder) {} }
            item { ActionRow("Refresh Library", "Manually trigger a full library refresh and re-cache thumbnails", Icons.Default.Refresh) {} }
            item { NavigationRow("Excluded Folders", "Manage list of ignored directories and .nomedia paths", Icons.Default.VisibilityOff) {} }
            item { 
                var checked by remember { mutableStateOf(false) }
                ToggleRow(
                    "Show Hidden Folders", 
                    "Display system folders and directories starting with a dot", 
                    checked = checked, 
                    onCheckedChange = { checked = it }
                ) 
            }
            item { SettingSectionHeader("Media Types") }
            item { NavigationRow("Filter Media Types", "Toggle visibility for Images, Videos, and animated GIFs", Icons.Default.FilterList) {} }
        }
    }
}

@Composable
fun TaggingAISettingsScreen(navController: NavController) {
    SettingsSubScreen("Tagging & AI", navController) {
        LazyColumn {
            item { SettingSectionHeader("AI Analysis") }
            item { ValueSelectorRow("Suggestion Confidence", "Medium", "Threshold for automatic tag proposals") {} }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Auto-merge Synonyms", "Groups similar tags like 'cat' and 'feline' automatically", checked = checked, onCheckedChange = { checked = it }) 
            }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Discovery Mode", "Suggest tags based on visual content similarities", checked = checked, onCheckedChange = { checked = it }) 
            }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Background Tagging", "Analyze new media immediately when added to gallery", checked = checked, onCheckedChange = { checked = it }) 
            }
            
            item { SettingSectionHeader("Tag Database") }
            item { NavigationRow("Manage Categories", "Organize tags into custom groups and hierarchies", Icons.Default.Category) {} }
            item { ActionRow("Rebuild Search Index", "Optimize the database for faster tag searching", Icons.Default.Build) {} }
        }
    }
}

@Composable
fun DisplaySettingsScreen(navController: NavController) {
    SettingsSubScreen("Display Settings", navController) {
        LazyColumn {
            item { SettingSectionHeader("Grid Layout") }
            item { ValueSelectorRow("Grid Column Count", "3 Columns", "Adjust thumbnail size in the main gallery") {} }
            item { 
                var checked by remember { mutableStateOf(false) }
                ToggleRow("Metadata Overlay", "Show file resolution and type icon on thumbnails", checked = checked, onCheckedChange = { checked = it }) 
            }
            item { ValueSelectorRow("Default Sort Order", "Date (Newest)", "Initial sorting for all folders") {} }
            
            item { SettingSectionHeader("Visual Theme") }
            item { ValueSelectorRow("App Theme", "System Default", "Switch between Light, Dark, or Schedule-based") {} }
            item { 
                ValueSelectorRow(
                    "Accent Color", 
                    "Deep Orange", 
                    "Choose the highlight color used for actions, tags, and switches"
                ) {} 
            }
            item { ValueSelectorRow("Animation Scale", "1.0x", "Adjust speed of UI transitions and effects") {} }
        }
    }
}

@Composable
fun PerformanceSettingsScreen(navController: NavController) {
    SettingsSubScreen("Performance", navController) {
        LazyColumn {
            item { SettingSectionHeader("Storage Maintenance") }
            item { 
                ActionRow(
                    "Clear Thumbnail Cache", 
                    "Removes cached previews to free up space. Images will reload slower temporarily.", 
                    Icons.Default.DeleteSweep,
                    iconColor = MaterialTheme.colorScheme.error
                ) {}
            }
            item { 
                ActionRow(
                    "Optimize Database", 
                    "Vacuum and rebuild indexes to improve app responsiveness", 
                    Icons.Default.Storage
                ) {}
            }
            
            item { SettingSectionHeader("Advanced Cleanup") }
            item { 
                ActionRow(
                    "Run Smart Clean-up", 
                    "Removes orphan tags, broken media references, and unused tag aliases", 
                    Icons.Default.CleaningServices
                ) {}
            }
        }
    }
}

@Composable
fun PrivacySettingsScreen(navController: NavController) {
    SettingsSubScreen("Privacy", navController) {
        LazyColumn {
            item { SettingSectionHeader("Content Protection") }
            item { NavigationRow("Sensitive Tags", "Manage tags that mark media items as private", Icons.Default.NoEncryption) {} }
            item { ValueSelectorRow("App Lock", "Fingerprint / PIN", "Secure access to the app with biometrics") {} }
            item { NavigationRow("Excluded AI Paths", "Prevent AI from scanning specific sensitive folders", Icons.Default.PsychologyAlt) {} }
        }
    }
}

@Composable
fun BackupDataSettingsScreen(navController: NavController) {
    SettingsSubScreen("Backup & Data", navController) {
        LazyColumn {
            item { SettingSectionHeader("Portability") }
            item { ActionRow("Export Tag Metadata", "Save your tag assignments to a portable JSON file", Icons.Default.Upload) {} }
            item { ActionRow("Import Tag Metadata", "Restore tag assignments from a previously exported file", Icons.Default.Download) {} }
            
            item { SettingSectionHeader("Full Backups") }
            item { ActionRow("Backup Database", "Create a full encrypted backup of all app settings and data", Icons.Default.CloudUpload) {} }
            item { ActionRow("Restore Database", "Restore the entire app state from a backup file", Icons.Default.CloudDownload) {} }
        }
    }
}

@Composable
fun AboutSettingsScreen(navController: NavController) {
    SettingsSubScreen("App Information", navController) {
        LazyColumn {
            item { SettingSectionHeader("Storage") }
            item { ActionRow("Cache Usage", "128 MB of storage used for previews", Icons.Default.Dns) {} }
            
            item { SettingSectionHeader("Stats") }
            item { ActionRow("Database Statistics", "View internal table sizes and record counts", Icons.Default.Analytics) {} }
            item { ActionRow("Library Size", "1,234 media items indexed", Icons.Default.Image) {} }
            item { ActionRow("Total Tags", "567 unique tags defined", Icons.AutoMirrored.Filled.Label) {} }
            
            item { SettingSectionHeader("App Preferences") }
            item { ActionRow("Version", "1.0.0 (Build 42)", Icons.Default.Info) {} }
            item { ActionRow("Open Source Licenses", "Legal information and third-party credits", Icons.Default.Description) {} }
            item { ActionRow("Debug Logging", "Generate and share logs for troubleshooting", Icons.Default.BugReport) {} }
            
            item { SettingSectionHeader("Support") }
            item { ActionRow("Report an Issue", "Send feedback or bug reports to the developers", Icons.Default.Email) {} }
        }
    }
}
