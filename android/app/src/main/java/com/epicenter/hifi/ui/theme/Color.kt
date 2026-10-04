package com.epicenter.hifi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

val PureBlack: Color @Composable get() = MaterialTheme.colorScheme.background
val DarkBackground: Color @Composable get() = MaterialTheme.colorScheme.background
val SurfaceDark: Color @Composable get() = MaterialTheme.colorScheme.surface
val CardSurface: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val BorderDark: Color @Composable get() = MaterialTheme.colorScheme.outline
val TextPrimary: Color @Composable get() = MaterialTheme.colorScheme.onBackground
val TextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val TextTertiary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
val AccentGold = Color(0xFFFF102A)
val AccentRed = Color(0xFFFF453A)
val AccentBlue = Color(0xFF0A84FF)
val DspActiveArc = Color(0xFFFF453A)
val TrackBackground = Color(0xFF242426)
