package com.epicenter.hifi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.viewmodel.PlayerViewModel

@Composable
fun QueueDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val state by viewModel.playbackState.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("COLA DE REPRODUCCIÓN", color = AccentRed, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    Text("${state.queue.size} canciones", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = viewModel::clearQueue) { Icon(Icons.Default.DeleteSweep, "Vaciar cola", tint = TextSecondary) }
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                itemsIndexed(state.queue, key = { _, track -> track.stableId }) { index, track ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 6.dp)) {
                            Text(track.title, color = if (index == state.queueIndex) AccentRed else TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { if (index > 0) viewModel.moveQueueItem(index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.Default.ArrowUpward, "Mover arriba", tint = TextSecondary)
                        }
                        IconButton(onClick = { if (index < state.queue.lastIndex) viewModel.moveQueueItem(index, index + 1) }, enabled = index < state.queue.lastIndex) {
                            Icon(Icons.Default.ArrowDownward, "Mover abajo", tint = TextSecondary)
                        }
                        IconButton(onClick = { viewModel.removeFromQueue(index) }) { Icon(Icons.Default.Close, "Quitar", tint = TextSecondary) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", color = AccentRed) } }
    )
}
