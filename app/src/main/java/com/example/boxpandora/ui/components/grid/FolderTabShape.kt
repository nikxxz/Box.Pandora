package com.example.boxpandora.ui.components.grid

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Custom Shape that recreates the SVG notch from the JS FolderCard.
 * Derived from the SVG path in FolderCard.js:
 * Card width = W
 * Tab lift = T (20.4% of panel)
 */
class FolderTabShape(private val tabLift: Float, private val tabRadius: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val t = tabLift
        val r = tabRadius

        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w * 0.362f, 0f)
            
            // Cubic A
            cubicTo(
                w * 0.383f, 0f,
                w * 0.402f, t * 0.063f,
                w * 0.416f, t * 0.174f
            )
            
            // Diagonal Line
            lineTo(w * 0.498f, t * 0.826f)
            
            // Cubic B
            cubicTo(
                w * 0.512f, t * 0.937f,
                w * 0.532f, t,
                w * 0.552f, t
            )
            
            lineTo(w, t)
            lineTo(w, h)
            lineTo(0f, h)
            lineTo(0f, r)
            
            // Top-left arc
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(0f, 0f, r * 2, r * 2),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            close()
        }
        return Outline.Generic(path)
    }
}
