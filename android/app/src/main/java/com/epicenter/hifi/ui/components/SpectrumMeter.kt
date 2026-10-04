package com.epicenter.hifi.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.DspActiveArc

@Composable
fun SpectrumMeter(
    spectrumBands: FloatArray,
    modifier: Modifier = Modifier,
    height: Dp = 80.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            val count = spectrumBands.size.coerceAtLeast(1)
            for (i in 0 until count) {
                // dB suele ir de -100dB a 0dB -> normalizamos a 0..1
                val db = if (i < spectrumBands.size) spectrumBands[i] else -100f
                val targetFraction = ((db + 90f) / 90f).coerceIn(0.04f, 1f)

                val animatedHeight by animateFloatAsState(
                    targetValue = targetFraction,
                    animationSpec = tween(durationMillis = 70),
                    label = "spectrum_bar_$i"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .fillMaxHeight(fraction = animatedHeight)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFFF3B30), DspActiveArc, Color(0xFFAF0B22))
                            )
                        )
                )
            }
        }
    }
}
