package com.epicenter.hifi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.epicenter.hifi.engine.AudioEngine
import com.epicenter.hifi.data.repository.FavoritesRepository
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
import com.epicenter.hifi.ui.theme.epicenterPageBackground
import com.epicenter.hifi.viewmodel.DspViewModel
import com.epicenter.hifi.viewmodel.EqViewModel
import com.epicenter.hifi.viewmodel.LibraryViewModel
import com.epicenter.hifi.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpicenterApp(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    dspViewModel: DspViewModel,
    eqViewModel: EqViewModel,
    audioEngine: AudioEngine,
    favoritesRepository: FavoritesRepository,
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
            if (showQueue) {
                showQueue = false
            } else {
                currentTab = when (currentTab) {
                    NavTab.SEARCH, NavTab.SETTINGS -> NavTab.LIBRARY
                    else -> NavTab.PLAYER
                }
            }
        }

        Box(Modifier.fillMaxSize().epicenterPageBackground()) {
            AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(tween(190)) + slideInHorizontally(
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                    ) { it / 18 } togetherWith
                        fadeOut(tween(150)) + slideOutHorizontally(tween(180)) { -it / 28 }
                },
                label = "native_navigation"
            ) { tab ->
                when (tab) {
                    NavTab.PLAYER -> PlayerScreen(
                        viewModel = playerViewModel,
                        favoritesRepository = favoritesRepository,
                        onDismiss = { currentTab = NavTab.LIBRARY },
                        onOpenQueue = { showQueue = true }
                    )
                    NavTab.LIBRARY -> LibraryScreen(
                        viewModel = libraryViewModel,
                        favoritesRepository = favoritesRepository,
                        onTrackClick = { currentTab = NavTab.PLAYER },
                        onImportAudio = onImportAudio,
                        onOpenSearch = { currentTab = NavTab.SEARCH },
                        onOpenSettings = { currentTab = NavTab.SETTINGS }
                    )
                    NavTab.SEARCH -> SearchScreen(
                        viewModel = libraryViewModel,
                        favoritesRepository = favoritesRepository,
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

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(
                    visible = playbackState.currentTrack != null && currentTab != NavTab.PLAYER,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 3 }
                ) {
                    val progress = if (playbackState.durationMs > 0L) {
                        playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()
                    } else 0f
                    MiniPlayer(
                        currentTrack = playbackState.currentTrack,
                        isPlaying = playbackState.isPlaying,
                        progressFraction = progress,
                        onTogglePlayPause = playerViewModel::togglePlayPause,
                        onNext = playerViewModel::next,
                        onClick = { currentTab = NavTab.PLAYER },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    )
                }
                BottomNavBar(
                    selectedTab = if (currentTab == NavTab.SEARCH || currentTab == NavTab.SETTINGS) NavTab.LIBRARY else currentTab,
                    onTabSelected = { currentTab = it },
                    epicenterEnabled = dspParams.enabled,
                    eqEnabled = eqParams.enabled,
                    effectsEnabled = effects.reverbEnabled || effects.concertHallEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (showQueue) QueueDialog(playerViewModel) { showQueue = false }
        }
      }
    }
}
