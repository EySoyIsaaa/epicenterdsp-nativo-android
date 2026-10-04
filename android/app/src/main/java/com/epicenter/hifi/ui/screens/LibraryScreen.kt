package com.epicenter.hifi.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.epicenter.hifi.ui.nativeText
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.CardSurface
import com.epicenter.hifi.ui.theme.DarkBackground
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.viewmodel.LibraryTab
import com.epicenter.hifi.viewmodel.LibraryViewModel
import com.epicenter.hifi.viewmodel.SortMode

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onTrackClick: (AudioTrack) -> Unit,
    onImportAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val artistGroups by viewModel.artistGroups.collectAsState()
    val albumGroups by viewModel.albumGroups.collectAsState()
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
    val selectedSongIds = remember { mutableStateListOf<String>() }
    val currentPlaylist = playlists.firstOrNull { it.id == currentPlaylistId }
    val visibleTracks = remember(allTracks, playlistTracks, searchQuery, sortMode, hiResOnly, currentPlaylistId) {
        val source = if (currentPlaylistId != null) playlistTracks else allTracks
        val searched = source.filter {
            (searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) || it.album.contains(searchQuery, true)) &&
                (!hiResOnly || it.isHiRes)
        }
        when (sortMode) {
            SortMode.DEFAULT -> searched
            SortMode.TITLE -> searched.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> searched.sortedBy { it.artist.lowercase() }
        }
    }

    BackHandler(enabled = currentPlaylistId != null) { currentPlaylistId = null }

    Column(modifier.fillMaxSize().background(DarkBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(nativeText("TU COLECCIÓN", "YOUR COLLECTION"), color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
                Text(
                    currentPlaylist?.name ?: nativeText("Música", "Music"),
                    color = TextPrimary,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (currentPlaylist != null) nativeText("${playlistTracks.size} canciones", "${playlistTracks.size} tracks")
                    else nativeText("${allTracks.size} canciones", "${allTracks.size} tracks"),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            if (currentPlaylistId != null) {
                IconButton(onClick = { currentPlaylistId = null }) { Icon(Icons.Default.ArrowBack, nativeText("Volver", "Back"), tint = TextPrimary) }
            } else {
                if (isScanning) {
                    CircularProgressIndicator(progress = { scanProgress }, color = AccentRed, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = viewModel::triggerScan) { Icon(Icons.Default.Refresh, nativeText("Buscar música", "Scan music"), tint = TextPrimary) }
                }
                IconButton(onClick = onImportAudio) { Icon(Icons.Default.PlaylistAdd, nativeText("Importar archivos", "Import files"), tint = AccentRed) }
            }
        }

        if (isScanning) {
            LinearProgressIndicator(progress = { scanProgress }, color = AccentRed, trackColor = CardSurface, modifier = Modifier.fillMaxWidth())
        }

        if (currentPlaylistId == null) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                placeholder = { Text(nativeText("Buscar canciones, artistas o álbumes", "Search songs, artists, or albums"), color = TextSecondary, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(16.dp)),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CardSurface, unfocusedContainerColor = CardSurface,
                    focusedBorderColor = AccentRed, unfocusedBorderColor = Color.Transparent, cursorColor = AccentRed
                )
            )

            val tabs = listOf(LibraryTab.SONGS, LibraryTab.ARTISTS, LibraryTab.ALBUMS, LibraryTab.PLAYLISTS)
            TabRow(
                selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
                containerColor = DarkBackground,
                contentColor = TextPrimary,
                indicator = { positions ->
                    val index = tabs.indexOf(selectedTab).coerceAtLeast(0)
                    TabRowDefaults.SecondaryIndicator(modifier = Modifier.tabIndicatorOffset(positions[index]), color = AccentRed, height = 2.dp)
                }
            ) {
                tabs.forEach { tab ->
                    val label = when (tab) {
                        LibraryTab.SONGS -> nativeText("Canciones", "Songs")
                        LibraryTab.ARTISTS -> nativeText("Artistas", "Artists")
                        LibraryTab.ALBUMS -> nativeText("Álbumes", "Albums")
                        LibraryTab.PLAYLISTS -> nativeText("Playlists", "Playlists")
                    }
                    Tab(selected = selectedTab == tab, onClick = { viewModel.setSelectedTab(tab) }, text = { Text(label, fontSize = 11.sp, fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal) })
                }
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
                    onPlay = { track -> viewModel.playAll(playlistTracks, playlistTracks.indexOfFirst { it.stableId == track.stableId }); onTrackClick(track) },
                    onMore = { selectedTrack = it },
                    onRemove = { viewModel.removeTrackFromPlaylist(currentPlaylistId!!, it) }
                )
            }
            selectedTab == LibraryTab.SONGS -> {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 7.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { hiResOnly = !hiResOnly }) {
                        Text(if (hiResOnly) "HI-RES ✓" else "HI-RES", color = if (hiResOnly) AccentRed else TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    var sortOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { sortOpen = true }) { Icon(Icons.Default.Sort, null, tint = TextSecondary); Text(nativeText("Ordenar", "Sort"), color = TextSecondary, fontSize = 11.sp) }
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            DropdownMenuItem(text = { Text(nativeText("Predeterminado", "Default")) }, onClick = { viewModel.setSortMode(SortMode.DEFAULT); sortOpen = false })
                            DropdownMenuItem(text = { Text(nativeText("Nombre", "Title")) }, onClick = { viewModel.setSortMode(SortMode.TITLE); sortOpen = false })
                            DropdownMenuItem(text = { Text(nativeText("Artista", "Artist")) }, onClick = { viewModel.setSortMode(SortMode.ARTIST); sortOpen = false })
                        }
                    }
                    IconButton(onClick = { if (visibleTracks.isNotEmpty()) { viewModel.playAll(visibleTracks.shuffled()); onTrackClick(visibleTracks.first()) } }) {
                        Icon(Icons.Default.Shuffle, nativeText("Aleatorio", "Shuffle"), tint = AccentRed)
                    }
                }
                TrackList(
                    tracks = visibleTracks,
                    onPlay = { track -> viewModel.playAll(visibleTracks, visibleTracks.indexOfFirst { it.stableId == track.stableId }); onTrackClick(track) },
                    onMore = { selectedTrack = it }
                )
            }
            selectedTab == LibraryTab.ARTISTS -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(artistGroups, key = { it.name }) { group ->
                        CollectionRow(group.name, nativeText("${group.tracks.size} canciones", "${group.tracks.size} tracks")) {
                            if (group.tracks.isNotEmpty()) { viewModel.playAll(group.tracks); onTrackClick(group.tracks.first()) }
                        }
                    }
                }
            }
            selectedTab == LibraryTab.ALBUMS -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
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
                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
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
    onPlay: (AudioTrack) -> Unit,
    onMore: (AudioTrack) -> Unit,
    onRemove: ((AudioTrack) -> Unit)? = null
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
        items(tracks, key = { it.stableId }) { track ->
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TrackListItem(track, onClick = { onPlay(track) }) }
                if (onRemove != null) IconButton(onClick = { onRemove(track) }) { Icon(Icons.Default.Delete, "Quitar de playlist", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                IconButton(onClick = { onMore(track) }) { Icon(Icons.Default.MoreVert, "Más opciones", tint = TextSecondary, modifier = Modifier.size(20.dp)) }
            }
        }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.isHiRes) {
                    Spacer(Modifier.width(5.dp))
                    Text("HI-RES", color = AccentRed, fontSize = 8.sp, fontWeight = FontWeight.Black)
                }
            }
            Text("${track.artist} · ${track.album}", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(track.formattedDuration, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
    }
}
