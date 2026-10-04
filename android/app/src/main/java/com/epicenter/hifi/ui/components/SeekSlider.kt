package com.epicenter.hifi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.TrackBackground

@Composable
fun SeekSlider(
    currentPositionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayPosition = if (isDragging) dragPositionMs.toLong() else currentPositionMs

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = displayPosition.toFloat().coerceIn(0f, safeDuration.toFloat()),
            onValueChange = {
                isDragging = true
                dragPositionMs = it
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(dragPositionMs.toLong())
            },
            valueRange = 0f..safeDuration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = AccentRed,
                inactiveTrackColor = TrackBackground
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(displayPosition),
                color = TextSecondary,
                fontSize = 12.sp
            )
            Text(
                text = formatTime(safeDuration),
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
