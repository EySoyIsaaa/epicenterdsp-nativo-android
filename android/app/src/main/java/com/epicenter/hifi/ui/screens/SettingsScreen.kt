package com.epicenter.hifi.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentGold
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.PureBlack
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.viewmodel.LibraryViewModel

@Composable
fun SettingsScreen(
    libraryViewModel: LibraryViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 120.dp)
    ) {
        Text(
            text = "Ajustes",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Configuración y motor de reproducción",
            color = TextSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Sección Motor de Audio
        SettingSectionTitle(title = "MOTOR DE AUDIO HI-FI")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardSurface)
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow(label = "Pipeline Nativo", value = "AndroidX Media3 + C++ JNI")
                SettingRow(label = "DSP Core", value = "The Epicenter Harmonics v2")
                SettingRow(label = "Ecualización", value = "31 bandas IIR (64-bit float)")
                SettingRow(label = "Soporte Hi-Res", value = "FLAC / WAV hasta 192 kHz")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Sección Biblioteca
        SettingSectionTitle(title = "BIBLIOTECA LOCAL")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardSurface)
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingRow(label = "Base de Datos", value = "Room SQLite (Cero latencia)")
                Button(
                    onClick = { libraryViewModel.triggerScan() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGold,
                        contentColor = PureBlack
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Volver a escanear música del dispositivo",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Acerca de
        SettingSectionTitle(title = "ACERCA DE")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardSurface)
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow(label = "Versión", value = "9.0.0 (100% Kotlin Compose)")
                SettingRow(label = "Arquitectura", value = "Android Full Native")
                SettingRow(label = "Licencia", value = "Epicenter Hi-Fi Audio")
            }
        }
    }
}

@Composable
private fun SettingSectionTitle(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextPrimary, fontSize = 14.sp)
        Text(text = value, color = TextSecondary, fontSize = 13.sp)
    }
}
