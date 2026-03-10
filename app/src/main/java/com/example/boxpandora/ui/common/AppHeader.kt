package com.example.boxpandora.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    title: String = "pandora",
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    // Replicating the JS AppHeader structure: Row with SpaceBetween
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Title Area
        Box(modifier = Modifier.weight(1f)) {
            Text(
                text = title.lowercase(),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.W200,
                    letterSpacing = 4.sp,
                    fontSize = 34.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Actions Area
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu, // Closest to 'sidebar' icon in standard set
                    contentDescription = "Settings",
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}
