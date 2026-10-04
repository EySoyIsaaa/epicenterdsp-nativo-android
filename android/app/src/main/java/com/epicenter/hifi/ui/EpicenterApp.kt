package com.epicenter.hifi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.engine.AudioEngine
import com.epicenter.hifi.ui.components.BottomNavBar
import com.epicenter.hifi.ui.components.MiniPlayer
import com.epicenter.hifi.ui.components.NavTab
import com.epicenter.hifi.ui.components.QueueDialog
import com.epicenter.hifi.ui.screens.DspScreen
import com.epicenter.hifi.ui.screens.EqScreen
import com.epicenter.hifi.ui.screens.FxScreen
import com.epicenter.hifi.ui.screens.LibraryScreen
import com.epicenter.hifi.ui.screens.PlayerScreen
import com.epicenter.hifi.ui.screens.SearchScreen
import com.epicenter.hifi.ui.screens.SettingsScreen
import com.epicenter.hifi.ui.LocalNativeLanguage
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.DarkBackground
import com.epicenter.hifi.ui.theme.EpicenterTheme
import com.epicenter.hifi.viewmodel.DspViewModel
import com.epicenter.hifi.viewmodel.EqViewModel
import com.epicenter.hifi.viewmodel.LibraryViewModel
import com.epicenter.hifi.viewmodel.PlayerViewModel

@Composable
fun NativeLaunchScreen() {
    EpicenterTheme {
        Box(Modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AccentRed, strokeWidth = 2.dp)
                Text("EPICENTER HI-FI", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpicenterApp(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    dspViewModel: DspViewModel,
    eqViewModel: EqViewModel,
    audioEngine: AudioEngine,
    onImportAudio: () -> Unit
) {
    val context = LocalContext.current
    val uiPreferences = remember {
        context.getSharedPreferences("epicenter_ui_preferences", android.content.Context.MODE_PRIVATE)
    }
    var isDark by remember { mutableStateOf(uiPreferences.getBoolean("theme.dark", true)) }
    var language by remember { mutableStateOf(uiPreferences.getString("language", "es") ?: "es") }

    EpicenterTheme(darkTheme = isDark) {
      CompositionLocalProvider(LocalNativeLanguage provides language) {
        var currentTab by remember { mutableStateOf(NavTab.PLAYER) }
        var showQueue by remember { mutableStateOf(false) }
        val playbackState by playerViewModel.playbackState.collectAsState()
        val dspParams by dspViewModel.dspParams.collectAsState()
        val eqParams by eqViewModel.eqParams.collectAsState()
        val effects by audioEngine.spatialEffects.collectAsState()

        BackHandler(enabled = showQueue || currentTab != NavTab.PLAYER) {
            if (showQueue) showQueue = false else currentTab = NavTab.PLAYER
        }

        Box(Modifier.fillMaxSize().background(DarkBackground)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = currentTab,
                        transitionSpec = {
                            fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 18 } togetherWith
                                fadeOut(tween(130)) + slideOutHorizontally(tween(130)) { -it / 24 }
                        },
                        label = "native_navigation"
                    ) { tab ->
                        when (tab) {
                            NavTab.PLAYER -> PlayerScreen(
                                viewModel = playerViewModel,
                                onDismiss = { currentTab = NavTab.LIBRARY },
                                onOpenQueue = { showQueue = true }
                            )
                            NavTab.LIBRARY -> LibraryScreen(
                                viewModel = libraryViewModel,
                                onTrackClick = { currentTab = NavTab.PLAYER },
                                onImportAudio = onImportAudio
                            )
                            NavTab.SEARCH -> SearchScreen(
                                viewModel = libraryViewModel,
                                onTrackSelected = { currentTab = NavTab.PLAYER }
                            )
                            NavTab.EPICENTER -> DspScreen(dspViewModel, eqViewModel)
                            NavTab.EQUALIZER -> EqScreen(eqViewModel)
                            NavTab.EFFECTS -> FxScreen(audioEngine)
                            NavTab.SETTINGS -> SettingsScreen(
                                libraryViewModel = libraryViewModel,
                                audioEngine = audioEngine,
                                onImportAudio = onImportAudio,
                                isDark = isDark,
                                onDarkChanged = { enabled ->
                                    isDark = enabled
                                    uiPreferences.edit().putBoolean("theme.dark", enabled).apply()
                                },
                                language = language,
                                onLanguageChanged = { selected ->
                                    language = selected
                                    uiPreferences.edit().putString("language", selected).apply()
                                }
                            )
                        }
                    }
                }

                if (playbackState.currentTrack != null && currentTab != NavTab.PLAYER) {
                    val progress = if (playbackState.durationMs > 0L) {
                        playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()
                    } else 0f
                    MiniPlayer(
                        currentTrack = playbackState.currentTrack,
                        isPlaying = playbackState.isPlaying,
                        progressFraction = progress,
                        onTogglePlayPause = playerViewModel::togglePlayPause,
                        onNext = playerViewModel::next,
                        onClick = { currentTab = NavTab.PLAYER }
                    )
                }
                BottomNavBar(
                    selectedTab = currentTab,
                    onTabSelected = { currentTab = it },
                    epicenterEnabled = dspParams.enabled,
                    eqEnabled = eqParams.enabled,
                    effectsEnabled = effects.reverbEnabled || effects.concertHallEnabled
                )
            }

            if (showQueue) QueueDialog(playerViewModel) { showQueue = false }
        }
      }
    }
}
