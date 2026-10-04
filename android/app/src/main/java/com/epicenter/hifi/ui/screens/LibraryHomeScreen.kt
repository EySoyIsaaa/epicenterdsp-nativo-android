package com.epicenter.hifi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.epicenter.hifi.data.model.AudioTrack
import com.epicenter.hifi.data.model.AudioQualityTier
import com.epicenter.hifi.ui.nativeText
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.components.floatingGlassSurface
import com.epicenter.hifi.ui.components.premiumCardSurface
import com.epicenter.hifi.ui.components.HiResAudioLogo
import com.epicenter.hifi.viewmodel.AlbumGroup
import com.epicenter.hifi.viewmodel.LibraryTab
import com.epicenter.hifi.data.repository.LocalPlaylist

@Composable
fun LibraryHomeContent(
    allTracks: List<AudioTrack>,
    favoriteCount: Int,
    artistCount: Int,
    albumGroups: List<AlbumGroup>,
    recentTracks: List<AudioTrack>,
    playlists: List<LocalPlaylist>,
    isScanning: Boolean,
    scanProgress: Float,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onImportAudio: () -> Unit,
    onScan: () -> Unit,
    onOpenCollection: (LibraryTab, Boolean) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayRecentTrack: (AudioTrack) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(bottom = 190.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeActionButton(Icons.Default.Search, nativeText("Buscar", "Search"), onOpenSearch, Modifier.size(44.dp))
            Row(
                modifier = Modifier.clip(CircleShape).floatingGlassSurface().padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeActionButton(Icons.Default.Settings, nativeText("Ajustes", "Settings"), onOpenSettings, Modifier.size(40.dp))
                HomeActionButton(Icons.Default.Add, nativeText("Importar música", "Import music"), onImportAudio, Modifier.size(40.dp), tint = AccentRed)
            }
        }

        Text(nativeText("Música", "Music"), color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Black)

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF321017), Color(0xFF130C0E))))
                .clickable(onClick = onShuffleAll).padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(15.dp)).background(AccentRed), contentAlignment = Alignment.Center) {
                if (isScanning) CircularProgressIndicator(progress = { scanProgress }, color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(25.dp))
                else Icon(Icons.Default.Shuffle, null, tint = Color.White, modifier = Modifier.size(31.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(nativeText("Reproducir aleatorio", "Shuffle all"), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(nativeText("${allTracks.size} canciones", "${allTracks.size} songs"), color = TextSecondary, fontSize = 13.sp)
            }
            Icon(Icons.Default.PlayArrow, null, tint = AccentRed, modifier = Modifier.size(26.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MusicCategoryRow(
                title = nativeText("Playlists", "Playlists"), count = playlists.size,
                icon = Icons.Default.PlaylistPlay, colors = listOf(Color(0xFFB544F2), Color(0xFF6535CC)),
                onClick = { onOpenCollection(LibraryTab.PLAYLISTS, false) }
            )
            MusicCategoryRow(
                title = nativeText("Favoritos", "Favorites"), count = favoriteCount,
                icon = Icons.Default.Favorite, colors = listOf(Color(0xFFFF4A74), Color(0xFFD72151)),
                onClick = { onOpenCollection(LibraryTab.FAVORITES, false) }
            )
            MusicCategoryRow(
                title = nativeText("Canciones", "Songs"), count = allTracks.size,
                icon = Icons.Default.MusicNote, colors = listOf(Color(0xFF3D92F5), Color(0xFF2460D3)),
                onClick = { onOpenCollection(LibraryTab.SONGS, false) }
            )
            MusicCategoryRow(
                title = nativeText("Artistas", "Artists"), count = artistCount,
                icon = Icons.Default.Mic, colors = listOf(Color(0xFFFF9635), Color(0xFFE55B16)),
                onClick = { onOpenCollection(LibraryTab.ARTISTS, false) }
            )
            MusicCategoryRow(
                title = nativeText("Álbumes", "Albums"), count = albumGroups.size,
                icon = Icons.Default.Album, colors = listOf(Color(0xFF35C7B8), Color(0xFF159888)),
                onClick = { onOpenCollection(LibraryTab.ALBUMS, false) }
            )
            MusicCategoryRow(
                title = nativeText("Alta resolución", "High resolution"), count = allTracks.count { it.qualityTier == AudioQualityTier.HI_RES },
                icon = Icons.Default.HighQuality, colors = listOf(Color(0xFF2A2723), Color(0xFF151312)), outlined = true,
                onClick = { onOpenCollection(LibraryTab.SONGS, true) }
            )
        }

        if (recentTracks.isNotEmpty()) {
            Text(nativeText("Escuchado recientemente", "Recently played"), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
            recentTracks.take(8).chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { track ->
                        RecentTrackPoster(track, Modifier.weight(1f), onClick = { onPlayRecentTrack(track) })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111011)).clickable(onClick = onScan).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Refresh, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Text(nativeText("Actualizar biblioteca", "Refresh library"), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun HomeActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary
) {
    Box(modifier.clip(CircleShape).floatingGlassSurface().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MusicCategoryRow(
    title: String,
    count: Int,
    icon: ImageVector,
    colors: List<Color>,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).premiumCardSurface()
            .then(if (outlined) Modifier.background(Color(0xFF121111)) else Modifier)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center
        ) {
            if (outlined) HiResAudioLogo(Modifier.size(38.dp))
            else Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count.toString(), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Default.ArrowForwardIos, null, tint = TextSecondary.copy(alpha = 0.75f), modifier = Modifier.padding(end = 3.dp).size(15.dp))
    }
}

@Composable
private fun RecentTrackPoster(track: AudioTrack, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(15.dp)).background(CardSurface), contentAlignment = Alignment.Center) {
            if (!track.albumArtUri.isNullOrBlank()) {
                AsyncImage(track.albumArtUri, contentDescription = track.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.LibraryMusic, null, tint = TextSecondary, modifier = Modifier.size(42.dp))
            }
        }
        Text(track.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        Text(track.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
