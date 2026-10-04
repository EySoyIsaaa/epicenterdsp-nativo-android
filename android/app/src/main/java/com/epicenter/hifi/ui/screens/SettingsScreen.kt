package com.epicenter.hifi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.epicenter.hifi.engine.AudioEngine
import com.epicenter.hifi.ui.nativeText
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.epicenterPageBackground
import com.epicenter.hifi.ui.components.premiumCardSurface
import com.epicenter.hifi.viewmodel.LibraryViewModel

@Composable
fun SettingsScreen(
    libraryViewModel: LibraryViewModel,
    audioEngine: AudioEngine,
    onImportAudio: () -> Unit,
    isDark: Boolean,
    onDarkChanged: (Boolean) -> Unit,
    language: String,
    onLanguageChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val crossfade by audioEngine.crossfade.collectAsState()
    val tracks by libraryViewModel.allTracks.collectAsState()
    var legalDocument by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier.fillMaxSize().epicenterPageBackground().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp).padding(bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(nativeText("CONFIGURACIÓN", "SETTINGS"), color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.7.sp)
        Text(nativeText("Ajustes", "Settings"), color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(nativeText("Personaliza la experiencia de escucha.", "Tune your listening experience."), color = TextSecondary, fontSize = 13.sp)

        SettingsCard {
            SettingsTitle(Icons.Default.DarkMode, nativeText("Apariencia", "Appearance"))
            SettingsToggle(
                title = nativeText("Tema oscuro", "Dark theme"),
                subtitle = if (isDark) nativeText("Activo", "On") else nativeText("Desactivado", "Off"),
                checked = isDark,
                onCheckedChange = onDarkChanged
            )
        }

        SettingsCard {
            SettingsTitle(Icons.Default.Language, nativeText("Idioma", "Language"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageChoice("Español", selected = language == "es", modifier = Modifier.weight(1f)) { onLanguageChanged("es") }
                LanguageChoice("English", selected = language == "en", modifier = Modifier.weight(1f)) { onLanguageChanged("en") }
            }
        }

        SettingsCard {
            SettingsTitle(Icons.Default.VolumeUp, nativeText("Reproducción", "Playback"))
            SettingsToggle(
                title = nativeText("Fundido entre canciones", "Track fade"),
                subtitle = nativeText("Suaviza el final y el inicio de cada pista", "Softens the end and start of each track"),
                checked = crossfade.enabled,
                onCheckedChange = { audioEngine.setCrossfade(enabled = it) }
            )
            if (crossfade.enabled) {
                Spacer(Modifier.height(8.dp))
                Text(nativeText("Duración: ${crossfade.durationSeconds} s", "Duration: ${crossfade.durationSeconds} s"), color = TextSecondary, fontSize = 12.sp)
                Slider(
                    value = crossfade.durationSeconds.toFloat(),
                    onValueChange = { audioEngine.setCrossfade(durationSeconds = it.toInt()) },
                    valueRange = 3f..10f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentRed)
                )
            }
        }

        SettingsCard {
            SettingsTitle(Icons.Default.LibraryMusic, nativeText("Biblioteca", "Library"))
            Text(nativeText("${tracks.size} canciones disponibles", "${tracks.size} tracks available"), color = TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { libraryViewModel.triggerScan() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Text(nativeText("Reescanear", "Rescan"), modifier = Modifier.padding(start = 5.dp), fontSize = 12.sp)
                }
                Button(
                    onClick = onImportAudio,
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AudioFile, null)
                    Text(nativeText("Importar", "Import"), modifier = Modifier.padding(start = 5.dp), fontSize = 12.sp)
                }
            }
        }

        SettingsCard {
            SettingsTitle(Icons.Default.AudioFile, nativeText("Acerca de Epicenter", "About Epicenter"))
            Text("Epicenter Hi-Fi · 12.0.0", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(nativeText("Reproductor local con procesamiento de audio nativo en tiempo real.", "Local music player with real-time native audio processing."), color = TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TextButton(onClick = { legalDocument = "privacy" }) { Text(nativeText("Privacidad", "Privacy"), color = AccentRed) }
                TextButton(onClick = { legalDocument = "terms" }) { Text(nativeText("Términos", "Terms"), color = AccentRed) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (legalDocument != null) {
        val isPrivacy = legalDocument == "privacy"
        AlertDialog(
            onDismissRequest = { legalDocument = null },
            title = { Text(if (isPrivacy) nativeText("Privacidad", "Privacy policy") else nativeText("Términos", "Terms of service")) },
            text = {
                Text(
                    if (isPrivacy) nativeText(
                        "Tu biblioteca y tus ajustes se guardan en este dispositivo. Epicenter Hi-Fi no sube tus archivos de música.",
                        "Your library and settings stay on this device. Epicenter Hi-Fi does not upload your music files."
                    ) else nativeText(
                        "Usa la aplicación con archivos de audio a los que tengas derecho de acceso. Los controles de sonido dependen de tu dispositivo y audífonos.",
                        "Use the app with audio files you are allowed to access. Sound controls depend on your device and headphones."
                    )
                )
            },
            confirmButton = { TextButton(onClick = { legalDocument = null }) { Text(nativeText("Cerrar", "Close"), color = AccentRed) } }
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().premiumCardSurface(RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingsTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = AccentRed)
        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = AccentRed))
    }
}

@Composable
private fun LanguageChoice(name: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = name,
        color = if (selected) Color.White else TextPrimary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentRed else androidx.compose.material3.MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(12.dp)
    )
}
