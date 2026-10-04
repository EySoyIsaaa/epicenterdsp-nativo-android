package com.epicenter.hifi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.epicenter.hifi.engine.RepeatMode
import com.epicenter.hifi.data.model.AudioQualityTier
import com.epicenter.hifi.data.repository.FavoritesRepository
import com.epicenter.hifi.ui.components.SeekSlider
import com.epicenter.hifi.ui.components.TrackFormatDetails
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.BorderDark
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.PureBlack
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.epicenterPageBackground
import com.epicenter.hifi.viewmodel.PlayerViewModel

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    favoritesRepository: FavoritesRepository,
    onDismiss: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.playbackState.collectAsState()
    val favoriteIds by favoritesRepository.favoriteIds.collectAsState()
    val track = state.currentTrack

    Column(
        modifier = modifier
            .fillMaxSize()
            .epicenterPageBackground()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra superior de navegación (Cerrar / Minimizar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Minimizar",
                    tint = TextPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = "REPRODUCIENDO",
                color = AccentRed,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            IconButton(onClick = onOpenQueue) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Cola",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Gran Arte del Álbum con sombra
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .aspectRatio(1f)
                .shadow(elevation = 28.dp, shape = RoundedCornerShape(24.dp), spotColor = AccentRed.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(24.dp))
                .background(CardSurface)
                .border(
                    width = when (track?.qualityTier) {
                        AudioQualityTier.HI_RES -> 2.dp
                        AudioQualityTier.CD -> 1.dp
                        else -> 0.dp
                    },
                    color = when (track?.qualityTier) {
                        AudioQualityTier.HI_RES -> Color(0xFFD8B45A)
                        AudioQualityTier.CD -> Color(0xFFC7CBD2)
                        else -> Color.Transparent
                    },
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (track != null && !track.albumArtUri.isNullOrEmpty()) {
                AsyncImage(
                    model = track.albumArtUri,
                    contentDescription = "Carátula",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(80.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Título, Artista y Calidad Hi-Res
        if (track != null) {
            Text(
                text = track.title,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = track.artist,
                color = TextSecondary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            TrackFormatDetails(track)
        } else {
            Text(
                text = "Sin reproducción activa",
                color = TextSecondary,
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Barra de progreso y Seek
        SeekSlider(
            currentPositionMs = state.currentPositionMs,
            durationMs = state.durationMs,
            onSeek = { viewModel.seekTo(it) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Controles de transporte principales
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Aleatorio
            IconButton(onClick = { viewModel.toggleShuffle() }) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Aleatorio",
                    tint = if (state.isShuffle) AccentRed else TextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Anterior
            IconButton(onClick = { viewModel.previous() }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Anterior",
                    tint = TextPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Play / Pause grande
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(TextPrimary),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { viewModel.togglePlayPause() }) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pausar" else "Reproducir",
                        tint = PureBlack,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            // Siguiente
            IconButton(onClick = { viewModel.next() }) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Siguiente",
                    tint = TextPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Repetir
            IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                val icon = when (state.repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                }
                Icon(
                    imageVector = icon,
                    contentDescription = "Repetir",
                    tint = if (state.repeatMode != RepeatMode.OFF) AccentRed else TextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { track?.let { favoritesRepository.toggleFavorite(it.stableId) } },
                enabled = track != null
            ) {
                Icon(
                    imageVector = if (track?.stableId in favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (track?.stableId in favoriteIds) AccentRed else TextSecondary,
                    modifier = Modifier.size(25.dp)
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(Icons.Default.QueueMusic, contentDescription = "Cola", tint = TextSecondary, modifier = Modifier.size(25.dp))
            }
        }
    }
}
