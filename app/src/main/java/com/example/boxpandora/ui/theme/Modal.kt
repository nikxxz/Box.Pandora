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
        border = colorScheme.outline.copy(alpha = if (isDark) 0.42f else 0.2f),
        divider = colorScheme.outlineVariant.copy(alpha = if (isDark) 0.8f else 0.65f),
        scrim = Color.Black.copy(alpha = if (isDark) 0.72f else 0.42f),
        titleText = colorScheme.onSurface,
        bodyText = colorScheme.onSurface,
        secondaryText = colorScheme.onSurfaceVariant,
        selectedAccent = colorScheme.primary,
        destructiveAccent = colorScheme.primary,
        handleColor = colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.5f else 0.35f),
        iconBackgroundNeutral = colorScheme.onSurface.copy(alpha = if (isDark) 0.08f else 0.05f),
        rowPressedBackground = colorScheme.onSurface.copy(alpha = if (isDark) 0.08f else 0.05f),
        sheetTopRadius = PandoraRadii.lg,
        dialogRadius = PandoraRadii.md,
        horizontalPadding = PandoraSpacing.xxl,
        topPadding = 0.dp,
        bottomPadding = PandoraSpacing.lg,
        sectionSpacing = PandoraSpacing.md,
        rowMinHeight = 56.dp,
        iconSize = 20.dp,
        iconChipSize = 40.dp,
        handleWidth = 48.dp,
        handleHeight = 4.dp,
        handleTopMargin = 8.dp
    )
}