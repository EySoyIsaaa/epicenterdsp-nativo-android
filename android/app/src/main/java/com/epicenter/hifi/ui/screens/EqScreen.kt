package com.epicenter.hifi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.components.Equalizer31Band
import com.epicenter.hifi.ui.components.SpectrumMeter
import com.epicenter.hifi.ui.theme.AccentGold
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.PureBlack
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.TrackBackground
import com.epicenter.hifi.viewmodel.EqViewModel

@Composable
fun EqScreen(
    viewModel: EqViewModel,
    modifier: Modifier = Modifier
) {
    val eqParams by viewModel.eqParams.collectAsState()
    val spectrumBands by viewModel.spectrumBands.collectAsState()
    val scrollState = rememberScrollState()
    var tuneResult by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 120.dp)
    ) {
        // Encabezado
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Ecualizador",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "31 bandas paramétricas (20 Hz - 20 kHz)",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Switch(
                checked = eqParams.enabled,
                onCheckedChange = { viewModel.setEqEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PureBlack,
                    checkedTrackColor = AccentGold,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = TrackBackground
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { tuneResult = viewModel.autoTune() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Optimizar con la canción actual", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        // Presets rápidos
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(viewModel.presets) { preset ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardSurface)
                        .clickable { viewModel.applyPreset(preset) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = preset.name,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Preamp Gain Slider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Preamp: ${if (eqParams.preampDb > 0) "+" else ""}${String.format("%.1f", eqParams.preampDb)} dB",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Slider(
                value = eqParams.preampDb,
                onValueChange = { viewModel.setPreamp(it) },
                valueRange = -12f..12f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = AccentGold,
                    inactiveTrackColor = TrackBackground
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )

            OutlinedButton(
                onClick = { viewModel.resetEq() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGold),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = "Reset", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Analizador de espectro
        Text(
            text = "ESPECTRO EN VIVO",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        SpectrumMeter(
            spectrumBands = spectrumBands,
            height = 65.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Contenedor de 31 bandas deslizables
        Text(
            text = "BANDAS DE FRECUENCIA",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Equalizer31Band(
            bands = eqParams.bands,
            frequencyLabels = viewModel.frequencyLabels,
            onBandChange = { index, gain -> viewModel.setBandGain(index, gain) }
        )
    }

    if (tuneResult != null) {
        AlertDialog(
            onDismissRequest = { tuneResult = null },
            title = { Text(if (tuneResult == true) "Ecualización optimizada" else "No hay suficiente señal") },
            text = { Text(if (tuneResult == true) "Se aplicó una corrección suave a las 31 bandas usando el espectro real de la reproducción." else "Reproduce una canción durante unos segundos y vuelve a intentarlo.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { tuneResult = null }) {
                    Text("Entendido", color = AccentRed)
                }
            }
        )
    }
}
