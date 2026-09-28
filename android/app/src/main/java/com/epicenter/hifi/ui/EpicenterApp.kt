package com.epicenter.hifi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.epicenter.hifi.ui.components.BottomNavBar
import com.epicenter.hifi.ui.components.MiniPlayer
import com.epicenter.hifi.ui.components.NavTab
import com.epicenter.hifi.ui.screens.DspScreen
import com.epicenter.hifi.ui.screens.EqScreen
import com.epicenter.hifi.ui.screens.LibraryScreen
import com.epicenter.hifi.ui.screens.PlayerScreen
import com.epicenter.hifi.ui.screens.SettingsScreen
import com.epicenter.hifi.ui.theme.EpicenterTheme
import com.epicenter.hifi.ui.theme.PureBlack
import com.epicenter.hifi.viewmodel.DspViewModel
import com.epicenter.hifi.viewmodel.EqViewModel
import com.epicenter.hifi.viewmodel.LibraryViewModel
import com.epicenter.hifi.viewmodel.PlayerViewModel

@Composable
fun EpicenterApp(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    dspViewModel: DspViewModel,
    eqViewModel: EqViewModel
) {
    EpicenterTheme {
        var currentTab by remember { mutableStateOf(NavTab.LIBRARY) }
        var showFullPlayer by remember { mutableStateOf(false) }

        val playbackState by playerViewModel.playbackState.collectAsState()
        val currentTrack = playbackState.currentTrack

        val progressFraction = if (playbackState.durationMs > 0L) {
            playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()
        } else 0f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
        ) {
            // Contenido de la pestaña activa
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        NavTab.LIBRARY -> LibraryScreen(
                            viewModel = libraryViewModel,
                            onTrackClick = { showFullPlayer = true }
                        )
                        NavTab.PLAYER -> PlayerScreen(
                            viewModel = playerViewModel,
                            onDismiss = { currentTab = NavTab.LIBRARY }
                        )
                        NavTab.EPICENTER -> DspScreen(
                            dspViewModel = dspViewModel,
                            eqViewModel = eqViewModel
                        )
                        NavTab.EQUALIZER -> EqScreen(
                            viewModel = eqViewModel
                        )
                        NavTab.SETTINGS -> SettingsScreen(
                            libraryViewModel = libraryViewModel
                        )
                    }
                }

                // MiniPlayer flotante + BottomNavBar
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (currentTrack != null && currentTab != NavTab.PLAYER && !showFullPlayer) {
                        MiniPlayer(
                            currentTrack = currentTrack,
                            isPlaying = playbackState.isPlaying,
                            progressFraction = progressFraction,
                            onTogglePlayPause = { playerViewModel.togglePlayPause() },
                            onNext = { playerViewModel.next() },
                            onClick = { showFullPlayer = true }
                        )
                    }

                    if (!showFullPlayer) {
                        BottomNavBar(
                            selectedTab = currentTab,
                            onTabSelected = { currentTab = it }
                        )
                    }
                }
            }

            // Reproductor completo desplegable
            AnimatedVisibility(
                visible = showFullPlayer,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                PlayerScreen(
                    viewModel = playerViewModel,
                    onDismiss = { showFullPlayer = false }
                )
            }
        }
    }
}
