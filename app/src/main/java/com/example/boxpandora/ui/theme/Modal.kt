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
        border = colorScheme.outline.copy(alpha = if (isDark) 0.24f else 0.10f),
        divider = colorScheme.outlineVariant.copy(alpha = if (isDark) 0.46f else 0.28f),
        scrim = Color.Black.copy(alpha = if (isDark) 0.78f else 0.5f),
        titleText = colorScheme.onSurface,
        bodyText = colorScheme.onSurface,
        secondaryText = colorScheme.onSurfaceVariant,
        tertiaryText = if (isDark) DarkTextTertiary else LightTextTertiary,
        cardBackground = if (isDark) colorScheme.surfaceVariant.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.92f),
        cardBorder = colorScheme.outline.copy(alpha = if (isDark) 0.32f else 0.16f),
        accentDim = colorScheme.primary.copy(alpha = 0.70f),
        selectedAccent = colorScheme.primary,
        destructiveAccent = colorScheme.error,
        handleColor = colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.30f else 0.20f),
        iconBackgroundNeutral = colorScheme.onSurface.copy(alpha = if (isDark) 0.07f else 0.035f),
        rowPressedBackground = colorScheme.onSurface.copy(alpha = if (isDark) 0.07f else 0.045f),
        sheetTopRadius = 32.dp,
        dialogRadius = 32.dp,
        horizontalPadding = 18.dp,
        topPadding = 10.dp,
        bottomPadding = 18.dp,
        sectionSpacing = 14.dp,
        rowMinHeight = 64.dp,
        iconSize = 20.dp,
        iconChipSize = 40.dp,
        handleWidth = 44.dp,
        handleHeight = 4.dp,
        handleTopMargin = 4.dp
    )
}