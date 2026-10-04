package com.epicenter.hifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.floatingGlassSurface(
    shape: Shape = RoundedCornerShape(28.dp)
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = if (dark) listOf(Color(0xD52A2A2D), Color(0xE5121214))
    else listOf(Color(0xEFFFFFFF), Color(0xE9E6E6EA))
    val border = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    return this
        .shadow(elevation = 22.dp, shape = shape, spotColor = Color.Black.copy(alpha = 0.32f))
        .clip(shape)
        .background(brush = Brush.verticalGradient(colors = fill))
        .border(1.dp, border, shape)
}

@Composable
fun Modifier.premiumCardSurface(
    shape: Shape = RoundedCornerShape(26.dp),
    highlighted: Boolean = false
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val borderColor = if (highlighted) AccentBorder else if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val fill = if (dark) listOf(Color(0xFF181719), Color(0xFF0C0B0D))
    else listOf(Color(0xFFFFFFFF), Color(0xFFECECF0))
    return this
        .shadow(elevation = 12.dp, shape = shape, spotColor = Color.Black.copy(alpha = 0.45f))
        .clip(shape)
        .background(
            brush = Brush.linearGradient(
                colors = fill
            )
        )
        .border(1.dp, borderColor, shape)
}

private val AccentBorder = Color(0x66FF1738)
