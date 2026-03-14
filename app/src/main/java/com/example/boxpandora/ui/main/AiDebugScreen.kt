package com.example.boxpandora.ui.main

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.boxpandora.PandoraApp
import com.example.boxpandora.ml.engine.SuggestionScore
import com.example.boxpandora.ui.main.viewmodel.AiDebugInfo
import com.example.boxpandora.ui.main.viewmodel.AiDebugViewModel
import com.example.boxpandora.ui.main.viewmodel.AiDebugViewModelFactory
import com.example.boxpandora.ui.theme.boxPandoraModalTokens
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDebugScreen(encodedUri: String, onBackClick: () -> Unit) {
    val mediaUri = android.net.Uri.decode(encodedUri)
    val context = LocalContext.current
    val app = context.applicationContext as PandoraApp
    val vm: AiDebugViewModel = viewModel(
        key = mediaUri,
        factory = AiDebugViewModelFactory(mediaUri, app.database, app.modelManager)
    )
    val info by vm.info.collectAsState()
    val tokens = boxPandoraModalTokens()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "AI Debug",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(start = 8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(tokens.iconBackgroundNeutral),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (info.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail
            item {
                AsyncImage(
                    model = Uri.parse(mediaUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // Status card
            item { DebugInfoCard(info) }

            // Top matches
            if (info.topMatches.isNotEmpty()) {
                item {
                    Text(
                        "Top prototype matches (unfiltered)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                items(info.topMatches) { score ->
                    ScoreRow(score)
                }
            } else if (!info.hasEmbedding) {
                item {
                    Text(
                        "No embedding — run Scene Index first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                item {
                    Text(
                        "No prototypes found — tag images and run Rebuild Index.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DebugInfoCard(info: AiDebugInfo) {
    val tokens = boxPandoraModalTokens()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tokens.cardBackground,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DebugRow(
                label = "Embedding",
                value = if (info.hasEmbedding) "Present (dim=${info.embeddingDim})" else "Missing",
                positive = info.hasEmbedding
            )
            DebugRow(label = "Model version", value = info.modelVersion ?: "None active")
            DebugRow(label = "Prototypes available", value = info.prototypeCount.toString())
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String, positive: Boolean? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = when (positive) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun ScoreRow(score: SuggestionScore) {
    val tokens = boxPandoraModalTokens()
    val finalPct = (score.finalConfidence * 100).roundToInt()
    val basePct = (score.baseSimilarity * 100).roundToInt()
    val boostPct = (score.cooccurrenceBoost * 100).roundToInt()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tokens.cardBackground,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    score.tagKey,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "base $basePct%" + if (boostPct > 0) " + boost $boostPct%" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    "${score.sampleCount} training samples",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            // Confidence badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    "$finalPct%",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
