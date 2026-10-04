package com.epicenter.hifi.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.epicenter.hifi.data.model.AudioTrack
import com.epicenter.hifi.data.model.AudioQualityTier
import com.epicenter.hifi.ui.components.AudioQualityBadge
import com.epicenter.hifi.data.repository.FavoritesRepository
import com.epicenter.hifi.ui.nativeText
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.AccentBlue
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.DarkBackground
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.epicenterPageBackground
import com.epicenter.hifi.ui.components.premiumCardSurface
import com.epicenter.hifi.viewmodel.LibraryTab
import com.epicenter.hifi.viewmodel.AlbumGroup
import com.epicenter.hifi.viewmodel.LibraryViewModel
import com.epicenter.hifi.viewmodel.SortMode

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    favoritesRepository: FavoritesRepository,
    onTrackClick: (AudioTrack) -> Unit,
    onImportAudio: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val favoriteIds by favoritesRepository.favoriteIds.collectAsState()
    val artistGroups by viewModel.artistGroups.collectAsState()
    val albumGroups by viewModel.albumGroups.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val playlistTracks by viewModel.selectedPlaylistTracks.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()

    var hiResOnly by remember { mutableStateOf(false) }
    var currentPlaylistId by remember { mutableStateOf<String?>(null) }
    var selectedTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var playlistTargetTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var playlistPickerOpen by remember { mutableStateOf(false) }
    var addSongsOpen by remember { mutableStateOf(false) }
    var createPlaylistOpen by remember { mutableStateOf(false) }
    var renamePlaylistOpen by remember { mutableStateOf(false) }
    var deletePlaylistOpen by remember { mutableStateOf(false) }
    var playlistMenuId by remember { mutableStateOf<String?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var showLibraryHome by remember { mutableStateOf(true) }
    val selectedSongIds = remember { mutableStateListOf<String>() }
    val currentPlaylist = playlists.firstOrNull { it.id == currentPlaylistId }
    val visibleTracks = remember(allTracks, playlistTracks, searchQuery, sortMode, hiResOnly, currentPlaylistId, selectedTab, favoriteIds) {
        val libraryTracks = if (selectedTab == LibraryTab.FAVORITES) allTracks.filter { it.stableId in favoriteIds } else allTracks
        val source = if (currentPlaylistId != null) playlistTracks else libraryTracks
        val searched = source.filter {
            (searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) || it.album.contains(searchQuery, true)) &&
                (!hiResOnly || it.qualityTier == AudioQualityTier.HI_RES)
        }
        when (sortMode) {
            SortMode.DEFAULT -> searched.sortedByDescending { it.dateAdded }
            SortMode.TITLE -> searched.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> searched.sortedBy { it.artist.lowercase() }
        }
    }

    BackHandler(enabled = currentPlaylistId != null || !showLibraryHome) {
        if (currentPlaylistId != null) currentPlaylistId = null else showLibraryHome = true
    }

    Column(modifier.fillMaxSize().epicenterPageBackground().statusBarsPadding()) {
      if (showLibraryHome) {
        LibraryHomeContent(
            allTracks = allTracks,
            favoriteCount = allTracks.count { it.stableId in favoriteIds },
            artistCount = artistGroups.size,
            albumGroups = albumGroups,
            recentTracks = recentlyPlayed,
            playlists = playlists,
            isScanning = isScanning,
            scanProgress = scanProgress,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onImportAudio = onImportAudio,
            onScan = viewModel::triggerScan,
            onOpenCollection = { tab, onlyHiRes ->
                viewModel.setSearchQuery("")
                hiResOnly = onlyHiRes
                viewModel.setSelectedTab(tab)
                showLibraryHome = false
            },
            onShuffleAll = {
                if (allTracks.isNotEmpty()) {
                    val shuffled = allTracks.shuffled()
                    viewModel.playAll(shuffled)
                    onTrackClick(shuffled.first())
                }
            },
            onPlayRecentTrack = { track ->
                viewModel.playTrack(track)
                onTrackClick(track)
            }
        )
      } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color(0x552C1D21)).clickable {
                    if (currentPlaylistId != null) currentPlaylistId = null else showLibraryHome = true
                },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.ArrowBack, nativeText("Volver", "Back"), tint = TextPrimary) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(nativeText("TU COLECCIÓN", "YOUR COLLECTION"), color = AccentRed, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                val title = currentPlaylist?.name ?: when (selectedTab) {
                    LibraryTab.SONGS -> nativeText("Canciones", "Songs")
                    LibraryTab.FAVORITES -> nativeText("Favoritos", "Favorites")
                    LibraryTab.ARTISTS -> nativeText("Artistas", "Artists")
                    LibraryTab.ALBUMS -> nativeText("Álbumes", "Albums")
                    LibraryTab.PLAYLISTS -> nativeText("Playlists", "Playlists")
                }
                Text(title, color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (currentPlaylist != null) nativeText("${playlistTracks.size} canciones", "${playlistTracks.size} tracks")
                    else nativeText("${visibleTracks.size} canciones", "${visibleTracks.size} tracks"),
                    color = TextSecondary, fontSize = 11.sp
                )
            }
            if (currentPlaylistId == null) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Color(0x552C1D21)).clickable(onClick = onOpenSearch),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Search, nativeText("Buscar", "Search"), tint = TextPrimary) }
            } else {
                Box(Modifier.size(42.dp))
            }
        }

        if (isScanning) {
            LinearProgressIndicator(progress = { scanProgress }, color = AccentRed, trackColor = CardSurface, modifier = Modifier.fillMaxWidth())
        }

        if (currentPlaylistId == null && (selectedTab == LibraryTab.SONGS || selectedTab == LibraryTab.FAVORITES)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LibraryActionButton(
                    label = nativeText("Reproducir", "Play"),
                    icon = Icons.Default.PlayArrow,
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = { if (visibleTracks.isNotEmpty()) { viewModel.playAll(visibleTracks); onTrackClick(visibleTracks.first()) } }
                )
                LibraryActionButton(
                    label = nativeText("Aleatorio", "Shuffle"),
                    icon = Icons.Default.Shuffle,
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { if (visibleTracks.isNotEmpty()) { val shuffled = visibleTracks.shuffled(); viewModel.playAll(shuffled); onTrackClick(shuffled.first()) } }
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
                    .clip(CircleShape).background(Color(0xFF282326)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    SortMode.DEFAULT to nativeText("Recientes", "Recent"),
                    SortMode.TITLE to nativeText("Nombre", "Name"),
                    SortMode.ARTIST to nativeText("Artista", "Artist")
                ).forEach { (mode, label) ->
                    val selected = sortMode == mode
                    Box(
                        Modifier.weight(1f).clip(CircleShape)
                            .background(if (selected) Color(0xFF625B60) else Color.Transparent)
                            .clickable { viewModel.setSortMode(mode) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            TextButton(onClick = { hiResOnly = !hiResOnly }, modifier = Modifier.padding(start = 12.dp)) {
                Text(if (hiResOnly) "HI-RES ✓" else "HI-RES", color = if (hiResOnly) AccentBlue else TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        when {
            currentPlaylistId != null -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (playlistTracks.isNotEmpty()) { viewModel.playAll(playlistTracks); onTrackClick(playlistTracks.first()) } }, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                        Icon(Icons.Default.PlayArrow, null); Text(nativeText("Reproducir", "Play"), modifier = Modifier.padding(start = 4.dp))
                    }
                    Button(onClick = { selectedSongIds.clear(); addSongsOpen = true }, colors = ButtonDefaults.buttonColors(containerColor = CardSurface)) {
                        Icon(Icons.Default.PlaylistAdd, null); Text(nativeText("Agregar canciones", "Add songs"), modifier = Modifier.padding(start = 4.dp), fontSize = 12.sp)
                    }
                    IconButton(onClick = { deletePlaylistOpen = true }) { Icon(Icons.Default.Delete, nativeText("Eliminar playlist", "Delete playlist"), tint = TextSecondary) }
                }
                TrackList(
                    tracks = visibleTracks,
                    favoriteIds = favoriteIds,
                    onToggleFavorite = favoritesRepository::toggleFavorite,
                    onPlay = { track -> viewModel.playAll(playlistTracks, playlistTracks.indexOfFirst { it.stableId == track.stableId }); onTrackClick(track) },
                    onMore = { selectedTrack = it },
                    onPlayNext = viewModel::playNext,
                    onAddToQueue = viewModel::addToQueue,
                    onAddToPlaylist = { playlistTargetTrack = it; playlistPickerOpen = true },
                    onRemove = { viewModel.removeTrackFromPlaylist(currentPlaylistId!!, it) }
                )
            }
            selectedTab == LibraryTab.SONGS || selectedTab == LibraryTab.FAVORITES -> {
                if (selectedTab == LibraryTab.FAVORITES && visibleTracks.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f).padding(top = 55.dp), contentAlignment = Alignment.TopCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FavoriteBorder, null, tint = TextSecondary, modifier = Modifier.size(34.dp))
                            Text(nativeText("Aún no tienes favoritos", "No favorites yet"), color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            Text(nativeText("Toca el corazón junto a una canción para guardarla aquí.", "Tap the heart beside a song to save it here."), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                } else {
                    TrackList(
                        tracks = visibleTracks,
                        favoriteIds = favoriteIds,
                        onToggleFavorite = favoritesRepository::toggleFavorite,
                        onPlay = { track -> viewModel.playAll(visibleTracks, visibleTracks.indexOfFirst { it.stableId == track.stableId }); onTrackClick(track) },
                        onMore = { selectedTrack = it },
                        onPlayNext = viewModel::playNext,
                        onAddToQueue = viewModel::addToQueue,
                        onAddToPlaylist = { playlistTargetTrack = it; playlistPickerOpen = true }
                    )
                }
            }
            selectedTab == LibraryTab.ARTISTS -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
                    items(artistGroups, key = { it.name }) { group ->
                        CollectionRow(group.name, nativeText("${group.tracks.size} canciones", "${group.tracks.size} tracks")) {
                            if (group.tracks.isNotEmpty()) { viewModel.playAll(group.tracks); onTrackClick(group.tracks.first()) }
                        }
                    }
                }
            }
            selectedTab == LibraryTab.ALBUMS -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
                    items(albumGroups, key = { "${it.name}||${it.artist}" }) { album ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (album.tracks.isNotEmpty()) { viewModel.playAll(album.tracks); onTrackClick(album.tracks.first()) } }.padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Artwork(album.albumArtUri, Modifier.size(54.dp))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(album.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${album.artist} · ${album.tracks.size} ${nativeText("pistas", "tracks")}", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            selectedTab == LibraryTab.PLAYLISTS -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nativeText("Tus playlists", "Your playlists"), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { playlistName = ""; createPlaylistOpen = true }, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                        Text(nativeText("Crear", "Create"), fontSize = 12.sp)
                    }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(Modifier.fillMaxWidth().clickable { currentPlaylistId = playlist.id; viewModel.loadPlaylist(playlist.id) }.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(CardSurface), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.LibraryMusic, null, tint = AccentRed)
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(playlist.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${playlist.trackIds.size} ${nativeText("canciones", "tracks")}", color = TextSecondary, fontSize = 11.sp)
                            }
                            Box {
                                IconButton(onClick = { playlistMenuId = playlist.id }) { Icon(Icons.Default.MoreVert, nativeText("Opciones", "Options"), tint = TextSecondary) }
                                DropdownMenu(expanded = playlistMenuId == playlist.id, onDismissRequest = { playlistMenuId = null }) {
                                    DropdownMenuItem(text = { Text(nativeText("Renombrar", "Rename")) }, onClick = { playlistName = playlist.name; currentPlaylistId = playlist.id; renamePlaylistOpen = true; playlistMenuId = null })
                                    DropdownMenuItem(text = { Text(nativeText("Eliminar", "Delete")) }, onClick = { currentPlaylistId = playlist.id; deletePlaylistOpen = true; playlistMenuId = null })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    selectedTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { selectedTrack = null },
            title = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = { viewModel.playTrack(track); onTrackClick(track); selectedTrack = null }) { Text(nativeText("Reproducir ahora", "Play now"), color = AccentRed) }
                    TextButton(onClick = { viewModel.playNext(track); selectedTrack = null }) { Text(nativeText("Reproducir después", "Play next"), color = TextPrimary) }
                    TextButton(onClick = { viewModel.addToQueue(track); selectedTrack = null }) { Text(nativeText("Agregar a la cola", "Add to queue"), color = TextPrimary) }
                    TextButton(onClick = { playlistTargetTrack = track; playlistPickerOpen = true; selectedTrack = null }) { Text(nativeText("Agregar a playlist", "Add to playlist"), color = TextPrimary) }
                    TextButton(onClick = { viewModel.removeFromLibrary(track); selectedTrack = null }) { Text(nativeText("Quitar de biblioteca", "Remove from library"), color = TextSecondary) }
                }
            },
            confirmButton = { TextButton(onClick = { selectedTrack = null }) { Text(nativeText("Cerrar", "Close"), color = TextSecondary) } }
        )
    }

    if (playlistPickerOpen) {
        AlertDialog(
            onDismissRequest = { playlistPickerOpen = false },
            title = { Text(nativeText("Agregar a playlist", "Add to playlist")) },
            text = {
                Column {
                    playlists.forEach { playlist ->
                        TextButton(onClick = {
                            playlistTargetTrack?.let { viewModel.addTrackToPlaylist(playlist.id, it) }
                            playlistPickerOpen = false
                        }) { Text(playlist.name, color = TextPrimary) }
                    }
                    TextButton(onClick = { playlistName = ""; createPlaylistOpen = true; playlistPickerOpen = false }) { Text(nativeText("Crear playlist nueva", "Create playlist"), color = AccentRed) }
                }
            },
            confirmButton = { TextButton(onClick = { playlistPickerOpen = false }) { Text(nativeText("Cerrar", "Close"), color = TextSecondary) } }
        )
    }

    if (createPlaylistOpen || renamePlaylistOpen) {
        val renaming = renamePlaylistOpen
        AlertDialog(
            onDismissRequest = { createPlaylistOpen = false; renamePlaylistOpen = false },
            title = { Text(if (renaming) nativeText("Renombrar playlist", "Rename playlist") else nativeText("Nueva playlist", "New playlist")) },
            text = {
                OutlinedTextField(value = playlistName, onValueChange = { playlistName = it }, singleLine = true, label = { Text(nativeText("Nombre", "Name")) })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renaming && currentPlaylistId != null) viewModel.renamePlaylist(currentPlaylistId!!, playlistName)
                    else viewModel.createPlaylist(playlistName) { id ->
                        playlistTargetTrack?.let { viewModel.addTrackToPlaylist(id, it) }
                    }
                    createPlaylistOpen = false; renamePlaylistOpen = false; playlistTargetTrack = null
                }) { Text(nativeText("Guardar", "Save"), color = AccentRed) }
            },
            dismissButton = { TextButton(onClick = { createPlaylistOpen = false; renamePlaylistOpen = false }) { Text(nativeText("Cancelar", "Cancel"), color = TextSecondary) } }
        )
    }

    if (deletePlaylistOpen) {
        AlertDialog(
            onDismissRequest = { deletePlaylistOpen = false },
            title = { Text(nativeText("Eliminar playlist", "Delete playlist")) },
            text = { Text(nativeText("Esta acción no se puede deshacer.", "This can't be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    currentPlaylistId?.let(viewModel::deletePlaylist)
                    currentPlaylistId = null
                    deletePlaylistOpen = false
                }) { Text(nativeText("Eliminar", "Delete"), color = AccentRed) }
            },
            dismissButton = { TextButton(onClick = { deletePlaylistOpen = false }) { Text(nativeText("Cancelar", "Cancel"), color = TextSecondary) } }
        )
    }

    if (addSongsOpen && currentPlaylistId != null) {
        AlertDialog(
            onDismissRequest = { addSongsOpen = false },
            title = { Text(nativeText("Agregar canciones", "Add songs")) },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(allTracks, key = { it.stableId }) { track ->
                        Row(Modifier.fillMaxWidth().clickable {
                            if (selectedSongIds.contains(track.stableId)) selectedSongIds.remove(track.stableId) else selectedSongIds.add(track.stableId)
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = selectedSongIds.contains(track.stableId), onCheckedChange = { checked ->
                                if (checked && !selectedSongIds.contains(track.stableId)) selectedSongIds.add(track.stableId)
                                if (!checked) selectedSongIds.remove(track.stableId)
                            })
                            Column {
                                Text(track.title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = allTracks.filter { selectedSongIds.contains(it.stableId) }
                    viewModel.addTracksToPlaylist(currentPlaylistId!!, chosen)
                    viewModel.loadPlaylist(currentPlaylistId!!)
                    selectedSongIds.clear(); addSongsOpen = false
                }) { Text(nativeText("Agregar", "Add"), color = AccentRed) }
            },
            dismissButton = { TextButton(onClick = { selectedSongIds.clear(); addSongsOpen = false }) { Text(nativeText("Cancelar", "Cancel"), color = TextSecondary) } }
        )
    }
}

@Composable
private fun TrackList(
    tracks: List<AudioTrack>,
    favoriteIds: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onPlay: (AudioTrack) -> Unit,
    onMore: (AudioTrack) -> Unit,
    onPlayNext: (AudioTrack) -> Unit,
    onAddToQueue: (AudioTrack) -> Unit,
    onAddToPlaylist: (AudioTrack) -> Unit,
    onRemove: ((AudioTrack) -> Unit)? = null
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
        items(tracks, key = { it.stableId }) { track ->
            SwipeTrackRow(
                track = track,
                isFavorite = track.stableId in favoriteIds,
                onPlay = { onPlay(track) },
                onToggleFavorite = { onToggleFavorite(track.stableId) },
                onAddToPlaylist = { onAddToPlaylist(track) },
                onPlayNext = { onPlayNext(track) },
                onAddToQueue = { onAddToQueue(track) },
                onMore = { onMore(track) },
                onRemove = onRemove?.let { remove -> { remove(track) } }
            )
        }
    }
}

@Composable
private fun SwipeTrackRow(
    track: AudioTrack,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onMore: () -> Unit,
    onRemove: (() -> Unit)?
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    var offsetPx by remember(track.stableId) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val favoriteWidth = with(density) { 82.dp.toPx() }
    val actionsWidth = with(density) { 204.dp.toPx() }
    val revealThreshold = with(density) { 32.dp.toPx() }

    fun runSwipeAction(action: () -> Unit) {
        action()
        scope.launch {
            Animatable(offsetPx).animateTo(0f, spring(dampingRatio = .82f, stiffness = 480f)) { offsetPx = value }
        }
    }

    fun animateOffsetTo(target: Float) {
        scope.launch {
            Animatable(offsetPx).animateTo(target, spring(dampingRatio = .78f, stiffness = 520f)) { offsetPx = value }
        }
    }

    Box(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(14.dp))) {
        Row(
            Modifier.align(Alignment.CenterStart).width(82.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { runSwipeAction(onToggleFavorite) }.padding(2.dp)) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFF0183B)), contentAlignment = Alignment.Center) {
                    Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text(nativeText("Favorito", "Favorite"), color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(
            Modifier.align(Alignment.CenterEnd).width(204.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SwipeAction(Icons.Default.PlaylistAdd, nativeText("Playlist", "Playlist"), Color(0xFF6858E8)) { runSwipeAction(onAddToPlaylist) }
            SwipeAction(Icons.Default.SkipNext, nativeText("Siguiente", "Play next"), Color(0xFF159CE8)) { runSwipeAction(onPlayNext) }
            SwipeAction(Icons.Default.QueueMusic, nativeText("A la cola", "Add to queue"), Color(0xFF77777E)) { runSwipeAction(onAddToQueue) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .shadow(2.dp, RoundedCornerShape(14.dp))
                .background(Color(0xFF09090B))
                .pointerInput(track.stableId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = when {
                                offsetPx > revealThreshold -> favoriteWidth
                                offsetPx < -revealThreshold -> -actionsWidth
                                else -> 0f
                            }
                            animateOffsetTo(target)
                        },
                        onDragCancel = { animateOffsetTo(0f) }
                    ) { change, dragAmount ->
                        change.consume()
                        offsetPx = (offsetPx + dragAmount).coerceIn(-actionsWidth, favoriteWidth)
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) { TrackListItem(track, onClick = onPlay) }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, nativeText("Favorito", "Favorite"), tint = if (isFavorite) AccentRed else TextSecondary, modifier = Modifier.size(18.dp))
            }
            if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Delete, "Quitar", tint = TextSecondary, modifier = Modifier.size(17.dp)) }
            IconButton(onClick = onMore, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.MoreVert, "Más opciones", tint = TextSecondary, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun SwipeAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable(onClick = onClick).padding(vertical = 3.dp)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Text(label, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun LibraryActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier.clip(CircleShape)
            .then(if (primary) Modifier.background(AccentRed) else Modifier.premiumCardSurface(RoundedCornerShape(30.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun CollectionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(CardSurface), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = AccentRed) }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Artwork(uri: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(11.dp)).background(CardSurface), contentAlignment = Alignment.Center) {
        if (!uri.isNullOrBlank()) AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.MusicNote, null, tint = TextSecondary)
    }
}

@Composable
fun TrackListItem(track: AudioTrack, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(track.albumArtUri, Modifier.size(46.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(track.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${track.artist} · ${track.album}", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AudioQualityBadge(track, compact = true)
        Text(track.formattedDuration, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
    }
}
