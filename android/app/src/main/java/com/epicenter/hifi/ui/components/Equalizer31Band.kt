package com.epicenter.hifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentGold
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.TextTertiary
import com.epicenter.hifi.ui.theme.TrackBackground

@Composable
fun Equalizer31Band(
    bands: FloatArray,
    frequencyLabels: List<String>,
    onBandChange: (index: Int, gainDb: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurface)
            .padding(vertical = 16.dp)
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
        ) {
            itemsIndexed(frequencyLabels) { index, label ->
                val currentGain = if (index < bands.size) bands[index] else 0f
                EqBandColumn(
                    index = index,
                    label = label,
                    gainDb = currentGain,
                    onGainChange = { newGain -> onBandChange(index, newGain) }
                )
            }
        }
    }
}

@Composable
private fun EqBandColumn(
    index: Int,
    label: String,
    gainDb: Float,
    onGainChange: (Float) -> Unit
) {
    val sliderHeight = 180.dp
    // -12dB a +12dB -> normalizado de 0f a 1f
    val normalized = ((gainDb + 12f) / 24f).coerceIn(0f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Valor numérico dB
        Text(
            text = "${if (gainDb > 0) "+" else ""}${String.format("%.1f", gainDb)}",
            color = if (gainDb != 0f) AccentGold else TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )

        // Pista vertical interactiva
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(sliderHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(TrackBackground)
                .pointerInput(index, gainDb) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val deltaDb = -dragAmount.y * 0.2f
                        val newDb = (gainDb + deltaDb).coerceIn(-12f, 12f)
                        onGainChange(newDb)
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            // Línea central de 0dB
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .background(Color(0xFF3A3A3C))
            )

            // Barra de ganancia
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight(fraction = normalized)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (gainDb != 0f) AccentGold else TextTertiary)
            )

            // Cabezal / Thumb del slider
            Box(
                modifier = Modifier
                    .padding(bottom = (normalized * 156).dp)
                    .width(20.dp)
                    .height(20.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }

        // Etiqueta de frecuencia
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
