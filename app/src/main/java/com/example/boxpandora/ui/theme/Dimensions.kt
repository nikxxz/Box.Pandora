package com.example.boxpandora.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object PandoraDimensions {
    val gridPadding = 16.dp
    val gridGap = 12.dp
    val cardBorderRadius = 14.dp
    val thumbGap = 2.dp
    
    @Composable
    fun cardWidth(): Dp {
        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        // (screenWidth - (side padding * 2) - spacing between cards) / 2
        return (screenWidth - (gridPadding * 2) - gridGap) / 2
    }
}
