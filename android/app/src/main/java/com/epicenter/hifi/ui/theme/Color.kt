package com.epicenter.hifi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance

val PureBlack: Color @Composable get() = MaterialTheme.colorScheme.background
val DarkBackground: Color @Composable get() = MaterialTheme.colorScheme.background
val SurfaceDark: Color @Composable get() = MaterialTheme.colorScheme.surface
val CardSurface: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val BorderDark: Color @Composable get() = MaterialTheme.colorScheme.outline
val TextPrimary: Color @Composable get() = MaterialTheme.colorScheme.onBackground
val TextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val TextTertiary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
val AccentGold = Color(0xFFE0B95B)
val AccentRed = Color(0xFFFF1738)
val AccentBlue = Color(0xFF35C8E7)
val MaroonGlow = Color(0xFF26090E)
val DspActiveArc = AccentRed
val TrackBackground = Color(0xFF242426)

@Composable
fun Modifier.epicenterPageBackground(): Modifier {
    val base = MaterialTheme.colorScheme.background
    return if (base.luminance() < 0.5f) {
        background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to MaroonGlow,
                    0.24f to Color(0xFF130609),
                    0.58f to Color(0xFF080708),
                    1f to Color(0xFF030303)
                )
            )
        )
    } else {
        background(base)
    }
}
