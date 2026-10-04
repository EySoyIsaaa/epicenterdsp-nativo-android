package com.epicenter.hifi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.engine.AudioEngine
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.DarkBackground
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary

@Composable
fun FxScreen(audioEngine: AudioEngine, modifier: Modifier = Modifier) {
    val effects by audioEngine.spatialEffects.collectAsState()
    Column(
        modifier = modifier.fillMaxSize().background(DarkBackground).padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ESPACIO Y AMBIENTE", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
        Text("Efectos", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Añade profundidad sin salir del procesamiento nativo.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        EffectCard(
            title = "Reverb",
            description = "Ambiente corto y controlado para dar espacio a la mezcla.",
            enabled = effects.reverbEnabled,
            amount = effects.reverbAmount,
            onEnabled = audioEngine::setReverbEnabled,
            onAmount = audioEngine::setReverbAmount
        )
        EffectCard(
            title = "Sala de conciertos",
            description = "Una cola amplia para una escena más abierta.",
            enabled = effects.concertHallEnabled,
            amount = effects.concertHallAmount,
            onEnabled = audioEngine::setConcertHallEnabled,
            onAmount = audioEngine::setConcertHallAmount
        )
    }
}

@Composable
private fun EffectCard(
    title: String,
    description: String,
    enabled: Boolean,
    amount: Float,
    onEnabled: (Boolean) -> Unit,
    onAmount: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardSurface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(description, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentRed)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INTENSIDAD", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Slider(
                value = amount,
                onValueChange = onAmount,
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentRed)
            )
            Text("${amount.toInt()}%", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
