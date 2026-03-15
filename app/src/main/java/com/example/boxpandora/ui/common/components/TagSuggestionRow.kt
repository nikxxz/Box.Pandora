package com.example.boxpandora.ui.common.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.boxpandora.data.local.entity.TagSuggestion
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

// ── Confidence band ───────────────────────────────────────────────────────────

private fun confidenceLabel(score: Double): String? = when {
    score >= 0.80 -> "High confidence"
    score >= 0.60 -> "Medium confidence"
    score >= 0.40 -> "Low confidence"
    else          -> null // hidden below 0.40
}

// ── Source label ──────────────────────────────────────────────────────────────

private fun sourceLabel(source: String): String = when {
    source.contains("person")               -> "person"
    source.contains("heuristic")            -> "rule"
    source.contains("cooccurrence")         -> "related"
    source.contains("prototype")            -> "learned"
    source.contains("scene")               -> "scene"
    else                                    -> "ai"
}

/**
 * A row-based suggestion item that replaces the old tiny `TagPopupSuggestionChip`.
 *
 * Layout:
 * ```
 * Row
 *  ├── Column
 *  │    ├── Tag name
 *  │    └── "Confidence • Source"
 *  └── [Add] [Dismiss]
 * ```
 *
 * Suggestions with a score below 0.40 are NOT shown — callers should pre-filter
 * with [shouldShowSuggestion].
 */
@Composable
fun TagSuggestionRow(
    suggestion: TagSuggestion,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = boxPandoraModalTokens()
    val accent = tokens.selectedAccent

    val confidence = confidenceLabel(suggestion.score) ?: return // hide if below threshold
    val srcLabel   = sourceLabel(suggestion.source)
    val metaText   = "$confidence • $srcLabel"

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: name + meta
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = suggestion.tagKey,
                            color = tokens.bodyText,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = metaText,
                            color = tokens.secondaryText.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Right: Add + Dismiss buttons
            TextButton(
                onClick = onAdd,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF8AE0A6)
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    "Add",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = tokens.secondaryText.copy(alpha = 0.7f)
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Returns true if this suggestion should be displayed (score >= 0.40). */
fun shouldShowSuggestion(suggestion: TagSuggestion): Boolean = suggestion.score >= 0.40
