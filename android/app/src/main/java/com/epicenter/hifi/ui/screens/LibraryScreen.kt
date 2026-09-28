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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.epicenter.hifi.data.model.AudioTrack
import com.epicenter.hifi.ui.theme.AccentGold
import com.epicenter.hifi.ui.theme.BorderDark
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.PureBlack
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.viewmodel.LibraryTab
import com.epicenter.hifi.viewmodel.LibraryViewModel

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onTrackClick: (AudioTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val tracks by viewModel.filteredTracks.collectAsState()
    val artistGroups by viewModel.artistGroups.collectAsState()
    val albumGroups by viewModel.albumGroups.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .statusBarsPadding()
    ) {
        // Encabezado
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Biblioteca",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${tracks.size} canciones",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            // Botón de Escanear / Rescanear
            if (isScanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { scanProgress },
                        color = AccentGold,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "${(scanProgress * 100).toInt()}%",
                        color = AccentGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                IconButton(onClick = { viewModel.triggerScan() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Escanear música",
                        tint = TextPrimary
                    )
                }
            }
        }

        // Barra de búsqueda
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurface)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = {
                    Text(
                        text = "Buscar en tu música...",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Limpiar",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = AccentGold,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Pestañas (Canciones, Artistas, Álbumes)
        val tabList = listOf(LibraryTab.SONGS to "Canciones", LibraryTab.ARTISTS to "Artistas", LibraryTab.ALBUMS to "Álbumes")
        TabRow(
            selectedTabIndex = tabList.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0),
            containerColor = PureBlack,
            contentColor = TextPrimary,
            indicator = { tabPositions ->
                val index = tabList.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[index]),
                    color = AccentGold,
                    height = 2.dp
                )
            },
            divider = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(BorderDark)
                )
            }
        ) {
            tabList.forEach { (tab, title) ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { viewModel.setSelectedTab(tab) },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                )
            }
        }

        // Contenido según la pestaña activa
        when (selectedTab) {
            LibraryTab.SONGS -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)
                ) {
                    items(tracks, key = { it.stableId }) { track ->
                        TrackListItem(
                            track = track,
                            onClick = {
                                viewModel.playTrack(track)
                                onTrackClick(track)
                            }
                        )
                    }
                }
            }

            LibraryTab.ARTISTS -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)
                ) {
                    items(artistGroups, key = { it.name }) { group ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (group.tracks.isNotEmpty()) {
                                        viewModel.playAll(group.tracks, 0)
                                        onTrackClick(group.tracks.first())
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = group.name,
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${group.tracks.size} canciones",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            LibraryTab.ALBUMS -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)
                ) {
                    items(albumGroups, key = { "${it.name}||${it.artist}" }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (album.tracks.isNotEmpty()) {
                                        viewModel.playAll(album.tracks, 0)
                                        onTrackClick(album.tracks.first())
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!album.albumArtUri.isNullOrEmpty()) {
                                AsyncImage(
                                    model = album.albumArtUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BorderDark)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CardSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column {
                                Text(
                                    text = album.name,
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${album.artist} • ${album.tracks.size} pistas",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            else -> {}
        }
    }
}

@Composable
fun TrackListItem(
    track: AudioTrack,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Carátula
        if (!track.albumArtUri.isNullOrEmpty()) {
            AsyncImage(
                model = track.albumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BorderDark)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Info de pista
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (track.isHiRes) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentGold.copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "HI-RES",
                            color = AccentGold,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Text(
                text = "${track.artist} — ${track.album}",
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Duración
        Text(
            text = track.formattedDuration,
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}
