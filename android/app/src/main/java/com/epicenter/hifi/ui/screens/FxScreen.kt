package com.epicenter.hifi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MeetingRoom
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
import com.epicenter.hifi.ui.components.KnobControl
import com.epicenter.hifi.ui.components.premiumCardSurface
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.epicenterPageBackground

@Composable
fun FxScreen(audioEngine: AudioEngine, modifier: Modifier = Modifier) {
    val effects by audioEngine.spatialEffects.collectAsState()
    Column(
        modifier = modifier.fillMaxSize().epicenterPageBackground().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp).padding(bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ESPACIO Y AMBIENTE", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
        Text("Efectos", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Añade profundidad sin salir del procesamiento nativo.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        EffectCard(
            title = "Reverb",
            label = "SPATIAL FX",
            description = "Ambiente corto y controlado para dar espacio a la mezcla.",
            icon = Icons.Default.GraphicEq,
            enabled = effects.reverbEnabled,
            amount = effects.reverbAmount,
            onEnabled = audioEngine::setReverbEnabled,
            onAmount = audioEngine::setReverbAmountRealtime,
            onAmountChangeFinished = audioEngine::persistCurrentSpatialEffects
        )
        EffectCard(
            title = "Concert Hall",
            label = "GRAN SALA",
            description = "Una cola amplia para una escena más abierta.",
            icon = Icons.Default.MeetingRoom,
            enabled = effects.concertHallEnabled,
            amount = effects.concertHallAmount,
            onEnabled = audioEngine::setConcertHallEnabled,
            onAmount = audioEngine::setConcertHallAmountRealtime,
            onAmountChangeFinished = audioEngine::persistCurrentSpatialEffects
        )
    }
}

@Composable
private fun EffectCard(
    title: String,
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    amount: Float,
    onEnabled: (Boolean) -> Unit,
    onAmount: (Float) -> Unit,
    onAmountChangeFinished: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).premiumCardSurface().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF151416)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(icon, null, tint = AccentRed, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(label, color = AccentRed, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.7.sp)
                Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentRed)
            )
        }
        Text(description, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        Text("${amount.toInt()}%", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.CenterHorizontally))
        Box(Modifier.align(Alignment.CenterHorizontally)) {
            KnobControl(
                label = "CANTIDAD",
                value = amount,
                minValue = 0f,
                maxValue = 100f,
                unit = "%",
                size = 126.dp,
                enabled = enabled,
                showValue = false,
                onValueChangeFinished = onAmountChangeFinished,
                onValueChange = onAmount
            )
        }
    }
}
