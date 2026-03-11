package com.example.boxpandora.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
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
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SettingSectionHeader("Library Scanning", "Configure how your media is discovered") }
            item { NavigationRow("Included Directories", "Choose which folders to scan for media", Icons.Default.Folder) {} }
            item { ActionRow("Refresh Library", "Manually trigger a full library refresh", Icons.Default.Refresh) {} }
            item { NavigationRow("Excluded Folders", "Manage list of ignored directories", Icons.Default.VisibilityOff) {} }
            item { 
                var checked by remember { mutableStateOf(false) }
                ToggleRow(
                    "Show Hidden Folders", 
                    "Display system folders and dotfiles", 
                    checked = checked, 
                    onCheckedChange = { checked = it }
                ) 
            }
            item { SettingSectionHeader("Media Types", "Choose what appears in your gallery") }
            item { NavigationRow("Filter Media Types", "Toggle Images, Videos, and GIFs", Icons.Default.FilterList) {} }
        }
    }
}

@Composable
fun TaggingAISettingsScreen(navController: NavController) {
    SettingsSubScreen("Tagging & AI", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SettingSectionHeader("AI Analysis", "Automatic categorization of your photos") }
            item { ValueSelectorRow("Suggestion Confidence", "Medium", "Threshold for automatic tag proposals") {} }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Auto-merge Synonyms", "Groups similar tags like 'cat' and 'feline'", checked = checked, onCheckedChange = { checked = it }) 
            }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Discovery Mode", "Suggest tags based on visual similarities", checked = checked, onCheckedChange = { checked = it }) 
            }
            item { 
                var checked by remember { mutableStateOf(true) }
                ToggleRow("Background Tagging", "Analyze new media immediately when added", checked = checked, onCheckedChange = { checked = it }) 
            }
            
            item { SettingSectionHeader("Tag Database", "Maintain your taxonomy") }
            item { NavigationRow("Manage Categories", "Organize tags into custom groups", Icons.Default.Category) {} }
            item { ActionRow("Rebuild Search Index", "Optimize for faster tag searching", Icons.Default.Build) {} }
        }
    }
}

@Composable
fun DisplaySettingsScreen(navController: NavController) {
    SettingsSubScreen("Display Settings", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SettingSectionHeader("Grid Layout", "Customize the gallery view") }
            item { ValueSelectorRow("Grid Column Count", "3 Columns", "Adjust thumbnail size in the main gallery") {} }
            item { 
                var checked by remember { mutableStateOf(false) }
                ToggleRow("Metadata Overlay", "Show resolution and type on thumbnails", checked = checked, onCheckedChange = { checked = it }) 
            }
            item { ValueSelectorRow("Default Sort Order", "Date (Newest)", "Initial sorting for all folders") {} }
            
            item { SettingSectionHeader("Visual Theme", "Personalize the app appearance") }
            item { ValueSelectorRow("App Theme", "System Default", "Light, Dark, or Schedule-based") {} }
            item { 
                ValueSelectorRow(
                    "Accent Color", 
                    "Deep Orange", 
                    "Color used for actions and highlights"
                ) {} 
            }
            item { ValueSelectorRow("Animation Scale", "1.0x", "Speed of UI transitions") {} }
        }
    }
}

@Composable
fun PerformanceSettingsScreen(navController: NavController) {
    SettingsSubScreen("Performance", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SettingSectionHeader("Storage Maintenance", "Keep the app running smoothly") }
            item { 
                ActionRow(
                    "Clear Thumbnail Cache", 
                    "Removes cached previews to free up space", 
                    Icons.Default.DeleteSweep,
                    iconColor = MaterialTheme.colorScheme.error
                ) {}
            }
            item { 
                ActionRow(
                    "Optimize Database", 
                    "Vacuum and rebuild indexes for better speed", 
                    Icons.Default.Storage
                ) {}
            }
            
            item { SettingSectionHeader("Advanced Cleanup", "Deep maintenance tasks") }
            item { 
                ActionRow(
                    "Run Smart Clean-up", 
                    "Removes orphan tags and broken references", 
                    Icons.Default.CleaningServices
                ) {}
            }
        }
    }
}

@Composable
fun PrivacySettingsScreen(navController: NavController) {
    SettingsSubScreen("Privacy", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SettingSectionHeader("Content Protection", "Secure your private media") }
            item { NavigationRow("Sensitive Tags", "Manage tags that mark items as private", Icons.Default.NoEncryption) {} }
            item { ValueSelectorRow("App Lock", "Fingerprint / PIN", "Secure access with biometrics") {} }
            item { NavigationRow("Excluded AI Paths", "Prevent AI from scanning specific folders", Icons.Default.PsychologyAlt) {} }
        }
    }
}

@Composable
fun BackupDataSettingsScreen(navController: NavController) {
    SettingsSubScreen("Backup & Data", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SettingSectionHeader("Portability", "Export or import your metadata") }
            item { ActionRow("Export Tag Metadata", "Save tag assignments to a JSON file", Icons.Default.Upload) {} }
            item { ActionRow("Import Tag Metadata", "Restore assignments from a file", Icons.Default.Download) {} }
            
            item { SettingSectionHeader("Full Backups", "Secure your entire database") }
            item { ActionRow("Backup Database", "Create an encrypted backup of all data", Icons.Default.CloudUpload) {} }
            item { ActionRow("Restore Database", "Restore app state from a backup", Icons.Default.CloudDownload) {} }
        }
    }
}

@Composable
fun AboutSettingsScreen(navController: NavController) {
    SettingsSubScreen("App Information", navController) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SettingSectionHeader("Storage", "Disk space usage") }
            item { ActionRow("Cache Usage", "128 MB used for previews", Icons.Default.Dns) {} }
            
            item { SettingSectionHeader("Stats", "Library overview") }
            item { ActionRow("Library Size", "1,234 media items indexed", Icons.Default.Image) {} }
            item { ActionRow("Total Tags", "567 unique tags defined", Icons.AutoMirrored.Filled.Label) {} }
            
            item { SettingSectionHeader("App Preferences", "Technical details") }
            item { ActionRow("Version", "1.0.0 (Build 42)", Icons.Default.Info) {} }
            item { ActionRow("Open Source Licenses", "Legal information", Icons.Default.Description) {} }
            item { ActionRow("Debug Logging", "Troubleshooting tools", Icons.Default.BugReport) {} }
            
            item { SettingSectionHeader("Support", "Get in touch") }
            item { ActionRow("Report an Issue", "Send feedback to the developers", Icons.Default.Email) {} }
        }
    }
}
