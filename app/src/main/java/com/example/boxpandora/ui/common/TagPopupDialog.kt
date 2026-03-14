package com.example.boxpandora.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.boxpandora.ui.theme.ModalTokens
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

@Composable
fun TagPopupDialog(
    title: String,
    subtitle: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAddClick: () -> Unit,
    addEnabled: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 720.dp,
    placeholder: String = "Search or create tag",
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = boxPandoraModalTokens()

    AppDialog(
        onDismiss = onDismiss,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        maxWidth = maxWidth,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = tokens.titleText,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = subtitle,
                color = tokens.secondaryText.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                tokens.cardBackground.copy(alpha = 0.88f)
            } else {
                Color.White.copy(alpha = 0.98f)
            },
            border = BorderStroke(1.dp, tokens.border),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModalTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = placeholder,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        onClick = onAddClick,
                        enabled = addEnabled,
                        shape = RoundedCornerShape(14.dp),
                        color = if (addEnabled) tokens.selectedAccent.copy(alpha = 0.14f) else tokens.iconBackgroundNeutral,
                        border = BorderStroke(1.dp, if (addEnabled) tokens.selectedAccent.copy(alpha = 0.28f) else tokens.border),
                        tonalElevation = 0.dp,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add tag",
                                tint = if (addEnabled) tokens.selectedAccent else tokens.secondaryText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                content()
            }
        }

        footer?.invoke(this)
    }
}

@Composable
fun TagPopupSectionLabel(
    title: String,
    meta: String
) {
    val tokens = boxPandoraModalTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = tokens.bodyText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = meta,
            color = tokens.secondaryText.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun TagPopupChip(
    label: String,
    backgroundColor: Color,
    borderColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailingIcon: ImageVector? = null,
    trailingTint: Color = textColor,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(textColor.copy(alpha = 0.5f))
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = textColor
            )
            count?.let {
                Text(
                    text = it.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
            }
            trailingIcon?.let {
                androidx.compose.material3.Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = trailingTint,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
fun TagPopupSuggestionChip(
    text: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = boxPandoraModalTokens()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tokens.rowPressedBackground)
            .border(1.dp, tokens.border, RoundedCornerShape(999.dp))
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text.uppercase(),
            color = tokens.bodyText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        TextButton(onClick = onAccept, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) {
            androidx.compose.material3.Icon(Icons.Default.Check, null, tint = Color(0xFF8AE0A6), modifier = Modifier.size(14.dp))
        }
        TextButton(onClick = onReject, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) {
            androidx.compose.material3.Icon(Icons.Default.Close, null, tint = Color(0xFFF36B6B), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun TagPopupFooter(
    dismissLabel: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val tokens = boxPandoraModalTokens()
    ModalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onDismiss) {
            Text(dismissLabel, color = tokens.secondaryText)
        }
        TextButton(onClick = onConfirm) {
            Text(
                confirmLabel,
                color = tokens.selectedAccent,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            )
        }
    }
}

fun tagPopupChipBackground(colorValue: String?, tokens: ModalTokens): Color {
    val base = parseTagPopupColor(colorValue) ?: tokens.iconBackgroundNeutral
    return base.copy(alpha = if (base.luminance() > 0.6f) 0.2f else 0.16f)
}

fun tagPopupChipBorder(colorValue: String?, tokens: ModalTokens): Color {
    val base = parseTagPopupColor(colorValue) ?: tokens.border
    return base.copy(alpha = if (base.luminance() > 0.6f) 0.34f else 0.28f)
}

fun parseTagPopupColor(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    return runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()
}