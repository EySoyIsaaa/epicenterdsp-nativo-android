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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
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
import com.epicenter.hifi.ui.components.KnobControl
import com.epicenter.hifi.ui.components.SpectrumMeter
import com.epicenter.hifi.ui.theme.AccentGold
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.PureBlack
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.TrackBackground
import com.epicenter.hifi.viewmodel.DspViewModel
import com.epicenter.hifi.viewmodel.EqViewModel

@Composable
fun DspScreen(
    dspViewModel: DspViewModel,
    eqViewModel: EqViewModel,
    modifier: Modifier = Modifier
) {
    val dspParams by dspViewModel.dspParams.collectAsState()
    val spectrumBands by eqViewModel.spectrumBands.collectAsState()
    val scrollState = rememberScrollState()
    var optimizeResult by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 120.dp)
    ) {
        // Encabezado con Switch de encendido
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Epicenter DSP",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Restaurador de Subgraves Hi-Fi",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Switch(
                checked = dspParams.enabled,
                onCheckedChange = { dspViewModel.setEpicenterEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PureBlack,
                    checkedTrackColor = AccentGold,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = TrackBackground
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Selector de Modo (Car Audio / Audífonos)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val isCar = dspParams.mode == "car"
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCar) AccentGold else Color.Transparent)
                    .clickable { dspViewModel.setMode("car") }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Car Audio (Potencia)",
                    color = if (isCar) PureBlack else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (!isCar) AccentGold else Color.Transparent)
                    .clickable { dspViewModel.setMode("headphones") }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Audífonos (Precisión)",
                    color = if (!isCar) PureBlack else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedButton(
            onClick = { optimizeResult = dspViewModel.autoOptimizeFromSpectrum() },
            enabled = dspParams.mode == "car",
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed, disabledContentColor = TextSecondary),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (dspParams.mode == "headphones") "Optimización automática solo en Car Audio" else "Optimizar con la canción actual", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        // Visualizador de Espectro
        Text(
            text = "RESPUESTA DE FRECUENCIA EN VIVO",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        SpectrumMeter(
            spectrumBands = spectrumBands,
            height = 70.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Knobs de Control en 2 filas
        // Fila 1: Sweep y Width (El núcleo del Epicenter)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            KnobControl(
                label = "Sweep (Frecuencia)",
                value = dspParams.sweepFreq,
                minValue = 27f,
                maxValue = 63f,
                unit = "Hz",
                size = 135.dp,
                onValueChange = { dspViewModel.setSweep(it) }
            )

            KnobControl(
                label = "Width (Ancho)",
                value = dspParams.width,
                minValue = 0f,
                maxValue = 100f,
                unit = "%",
                size = 135.dp,
                onValueChange = { dspViewModel.setWidth(it) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Fila 2: Intensity, Balance, Volume
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            KnobControl(
                label = "Intensidad",
                value = dspParams.intensity,
                minValue = 0f,
                maxValue = 100f,
                unit = "%",
                size = 105.dp,
                onValueChange = { dspViewModel.setIntensity(it) }
            )

            KnobControl(
                label = "Balance",
                value = dspParams.balance,
                minValue = 0f,
                maxValue = 100f,
                unit = "%",
                size = 105.dp,
                onValueChange = { dspViewModel.setBalance(it) }
            )

            KnobControl(
                label = "Volumen",
                value = dspParams.volume,
                minValue = 0f,
                maxValue = 100f,
                unit = "%",
                size = 105.dp,
                onValueChange = { dspViewModel.setVolume(it) }
            )
        }
    }

    if (optimizeResult != null) {
        AlertDialog(
            onDismissRequest = { optimizeResult = null },
            title = { Text(if (optimizeResult == true) "Epicenter optimizado" else "No hay suficiente señal") },
            text = { Text(if (optimizeResult == true) "Se ajustaron Sweep, Width e Intensidad a partir del espectro real. Balance y Volumen se conservaron." else "Reproduce una canción durante unos segundos y vuelve a intentarlo.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { optimizeResult = null }) {
                    Text("Entendido", color = AccentRed)
                }
            }
        )
    }
}
