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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.components.SpectrumMeter
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.PureBlack
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.TrackBackground
import com.epicenter.hifi.ui.theme.epicenterPageBackground
import com.epicenter.hifi.ui.components.premiumCardSurface
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
    val isCar = dspParams.mode == "car"
    val carBackground by androidx.compose.animation.animateColorAsState(
        if (isCar) AccentRed else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "car_mode_background"
    )
    val headphoneBackground by androidx.compose.animation.animateColorAsState(
        if (!isCar) AccentRed else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "headphone_mode_background"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .epicenterPageBackground()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
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
                    text = "Restaurador de bajos",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (dspParams.enabled) "ACTIVO" else "BYPASS",
                    color = if (dspParams.enabled) AccentRed else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Switch(
                    checked = dspParams.enabled,
                    onCheckedChange = { dspViewModel.setEpicenterEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PureBlack,
                        checkedTrackColor = AccentRed,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = TrackBackground
                    )
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 188.dp)
        ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Selector de Modo (Car Audio / Audífonos)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .premiumCardSurface()
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(carBackground)
                    .clickable { dspViewModel.setMode("car") }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Car Audio",
                    color = if (isCar) Color.White else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(headphoneBackground)
                    .clickable { dspViewModel.setMode("headphones") }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Audífonos",
                    color = if (!isCar) Color.White else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Text(
            text = if (dspParams.mode == "headphones")
                "Tuning para audífonos y bocinas portátiles: graves profundos y limpios incluso en drivers pequeños."
            else "Tuning para sistemas de car audio con mayor impacto y control de graves.",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
        )

        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .premiumCardSurface(RoundedCornerShape(26.dp), highlighted = true)
                .padding(horizontal = 18.dp, vertical = 17.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.clip(CircleShape).background(Color(0x331B0B0F)).padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(
                    if (dspParams.enabled) "●  EPICENTER ENGINE ACTIVE" else "●  EPICENTER ENGINE STANDBY",
                    color = if (dspParams.enabled) AccentRed else TextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp
                )
            }
            DspKnob(
                label = "INTENSIDAD",
                value = dspParams.intensity,
                unit = "%",
                size = 180.dp,
                onValueChangeFinished = dspViewModel::persistKnobValues,
                onValueChange = { dspViewModel.setIntensity(it) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

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
            DspKnob(
                label = "Sweep (Frecuencia)",
                value = dspParams.sweepFreq,
                unit = "Hz",
                size = 135.dp,
                enabled = isCar,
                onValueChangeFinished = dspViewModel::persistKnobValues,
                onValueChange = { dspViewModel.setSweep(it) }
            )

            DspKnob(
                label = "Width (Ancho)",
                value = dspParams.width,
                unit = "%",
                size = 135.dp,
                enabled = isCar,
                onValueChangeFinished = dspViewModel::persistKnobValues,
                onValueChange = { dspViewModel.setWidth(it) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Fila 2: Intensity, Balance, Volume
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DspKnob(
                label = "Balance",
                value = dspParams.balance,
                unit = "%",
                size = 105.dp,
                enabled = isCar,
                onValueChangeFinished = dspViewModel::persistKnobValues,
                onValueChange = { dspViewModel.setBalance(it) }
            )

            DspKnob(
                label = "Volumen",
                value = dspParams.volume,
                unit = "%",
                size = 105.dp,
                enabled = isCar,
                onValueChangeFinished = dspViewModel::persistKnobValues,
                onValueChange = { dspViewModel.setVolume(it) }
            )
        }
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

@Composable
private fun DspKnob(
    label: String,
    value: Float,
    unit: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit,
    onValueChange: (Float) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (enabled) 1f else 0.34f)
    ) {
        Text(
            text = "${value.toInt()}$unit",
            color = TextPrimary,
            fontSize = if (size >= 160.dp) 32.sp else 16.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(bottom = if (size >= 160.dp) 0.dp else 3.dp)
        )
        com.epicenter.hifi.ui.components.KnobControl(
            label = label,
            value = value,
            minValue = if (unit == "Hz") 27f else 0f,
            maxValue = if (unit == "Hz") 63f else 100f,
            unit = unit,
            size = size,
            enabled = enabled,
            showValue = false,
            onValueChangeFinished = onValueChangeFinished,
            onValueChange = onValueChange
        )
    }
}
