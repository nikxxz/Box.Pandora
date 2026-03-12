package com.example.boxpandora.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.boxpandora.ui.main.viewmodel.AccentColor
import com.example.boxpandora.ui.main.viewmodel.ThemeMode
import androidx.compose.ui.graphics.luminance

private val DarkColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkTextPrimary,
    primaryContainer = DarkAccentDim,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkCard,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkDivider
)

private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightSurface,
    primaryContainer = LightAccentDim,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightCard,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = LightDivider
)

@Composable
fun BoxPandoraTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    showGradient: Boolean = true,
    accentColor: AccentColor = AccentColor.EMBER_RED,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }

    val accentPrimary   = Color(accentColor.colorLong)
    val accentOnPrimary = if (accentPrimary.luminance() > 0.5f) Color(0xFF111111) else Color.White
    val accentContainer = accentPrimary.copy(alpha = 0.20f)
    val colorScheme = (if (darkTheme) DarkColorScheme else LightColorScheme).copy(
        primary          = accentPrimary,
        onPrimary        = accentOnPrimary,
        primaryContainer = accentContainer
    )
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()

            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        if (showGradient && darkTheme) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF000000),
                                Color(0xFF0A0A0A)
                            )
                        )
                    )
            ) {
                content()
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
            ) {
                content()
            }
        }
    }
}
