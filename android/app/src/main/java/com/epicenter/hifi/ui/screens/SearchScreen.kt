package com.epicenter.hifi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.DarkBackground
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.viewmodel.LibraryViewModel

@Composable
fun SearchScreen(
    viewModel: LibraryViewModel,
    onTrackSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val library by viewModel.allTracks.collectAsState()
    var query by remember { mutableStateOf("") }
    val results = remember(library, query) {
        if (query.isBlank()) emptyList() else library.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(DarkBackground).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.padding(top = 22.dp)) {
            Text("BIBLIOTECA LOCAL", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
            Text("Buscar", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Encuentra canciones, artistas y álbumes", color = TextSecondary, fontSize = 13.sp)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)),
            placeholder = { Text("¿Qué quieres escuchar?", color = TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = AccentRed) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = Color(0xFF101013),
                unfocusedContainerColor = Color(0xFF101013),
                focusedBorderColor = AccentRed,
                unfocusedBorderColor = Color(0xFF28282D),
                cursorColor = AccentRed
            )
        )
        if (query.isNotBlank()) {
            Text("${results.size} resultados", color = TextSecondary, fontSize = 12.sp)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { _, track -> track.stableId }) { index, track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                            TrackListItem(track) {
                                viewModel.playAll(results, index)
                                onTrackSelected()
                            }
                        }
                        IconButton(onClick = { viewModel.playNext(track) }) {
                            Icon(Icons.Default.QueueMusic, "Reproducir después", tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
