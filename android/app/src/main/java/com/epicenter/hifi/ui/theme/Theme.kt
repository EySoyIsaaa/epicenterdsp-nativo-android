package com.epicenter.hifi.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = AccentRed,
    onSecondary = Color.White,
    tertiary = AccentBlue,
    background = Color(0xFF08080A),
    onBackground = Color.White,
    surface = Color(0xFF141416),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF9A9AA1),
    outline = Color(0xFF2C2C2E)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF17171A),
    onPrimary = Color.White,
    secondary = Color(0xFFD61F34),
    onSecondary = Color.White,
    tertiary = Color(0xFF0A84FF),
    background = Color(0xFFF4F4F6),
    onBackground = Color(0xFF151518),
    surface = Color.White,
    onSurface = Color(0xFF151518),
    surfaceVariant = Color(0xFFE8E8EC),
    onSurfaceVariant = Color(0xFF66666E),
    outline = Color(0xFFD3D3D8)
)

@Composable
fun EpicenterTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val systemBarColor = colorScheme.background.toArgb()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = systemBarColor
                window.navigationBarColor = systemBarColor
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
