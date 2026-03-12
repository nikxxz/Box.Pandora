package com.example.boxpandora.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class ModalTokens(
    val background: Color,
    val border: Color,
    val divider: Color,
    val scrim: Color,
    val titleText: Color,
    val bodyText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val cardBackground: Color,
    val cardBorder: Color,
    val accentDim: Color,
    val selectedAccent: Color,
    val destructiveAccent: Color,
    val handleColor: Color,
    val iconBackgroundNeutral: Color,
    val rowPressedBackground: Color,
    val sheetTopRadius: Dp,
    val dialogRadius: Dp,
    val horizontalPadding: Dp,
    val topPadding: Dp,
    val bottomPadding: Dp,
    val sectionSpacing: Dp,
    val rowMinHeight: Dp,
    val iconSize: Dp,
    val iconChipSize: Dp,
    val handleWidth: Dp,
    val handleHeight: Dp,
    val handleTopMargin: Dp
)

@Composable
fun boxPandoraModalTokens(): ModalTokens {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f

    return ModalTokens(
        background = colorScheme.surface,
        border = colorScheme.outline.copy(alpha = if (isDark) 0.28f else 0.14f),
        divider = colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.42f),
        scrim = Color.Black.copy(alpha = if (isDark) 0.78f else 0.5f),
        titleText = colorScheme.onSurface,
        bodyText = colorScheme.onSurface,
        secondaryText = colorScheme.onSurfaceVariant,
        tertiaryText = if (isDark) DarkTextTertiary else LightTextTertiary,
        cardBackground = colorScheme.surfaceVariant,
        cardBorder = colorScheme.outline.copy(alpha = if (isDark) 0.40f else 0.22f),
        accentDim = colorScheme.primary.copy(alpha = 0.70f),
        selectedAccent = colorScheme.primary,
        destructiveAccent = colorScheme.error,
        handleColor = colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.36f else 0.22f),
        iconBackgroundNeutral = colorScheme.onSurface.copy(alpha = if (isDark) 0.06f else 0.04f),
        rowPressedBackground = colorScheme.onSurface.copy(alpha = if (isDark) 0.06f else 0.04f),
        sheetTopRadius = 28.dp,
        dialogRadius = 28.dp,
        horizontalPadding = 20.dp,
        topPadding = 12.dp,
        bottomPadding = 20.dp,
        sectionSpacing = 12.dp,
        rowMinHeight = 60.dp,
        iconSize = 20.dp,
        iconChipSize = 42.dp,
        handleWidth = 56.dp,
        handleHeight = 5.dp,
        handleTopMargin = 6.dp
    )
}