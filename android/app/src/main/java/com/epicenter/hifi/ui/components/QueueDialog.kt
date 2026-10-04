package com.epicenter.hifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.epicenter.hifi.data.model.AudioTrack
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.viewmodel.PlayerViewModel
import kotlin.math.roundToInt

@Composable
fun QueueDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val state by viewModel.playbackState.collectAsState()
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    var editMode by remember { mutableStateOf(false) }
    var draggedTrackId by remember { mutableStateOf<String?>(null) }
    var dropTargetTrackId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val maxHeight = (configuration.screenHeightDp.dp * .84f).coerceAtMost(760.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .heightIn(min = 240.dp, max = maxHeight)
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF302F32), Color(0xFF1D1C1F))))
                .border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(30.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Listo",
                    color = AccentRed,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Cola", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("${state.queue.size} canciones", color = TextSecondary, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { editMode = !editMode }) {
                        if (editMode) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(AccentRed), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, "Terminar edición", tint = Color.White, modifier = Modifier.size(21.dp))
                            }
                        } else {
                            Text("Editar", color = AccentRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (state.queue.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearQueue) {
                            Icon(Icons.Default.DeleteSweep, "Vaciar cola", tint = TextSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
            Spacer(Modifier.height(4.dp))

            if (state.queue.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("La cola está vacía", color = TextSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = listState,
                    userScrollEnabled = !editMode
                ) {
                    itemsIndexed(state.queue, key = { _, track -> track.stableId }) { index, track ->
                        QueueTrackRow(
                            track = track,
                            isCurrent = index == state.queueIndex,
                            editing = editMode,
                            isDropTarget = dropTargetTrackId == track.stableId && draggedTrackId != track.stableId,
                            dragOffsetY = if (draggedTrackId == track.stableId) dragOffsetY else 0f,
                            onClick = { viewModel.playTrackAtIndex(index) },
                            onRemove = { viewModel.removeFromQueue(index) },
                            onDragStart = {
                                draggedTrackId = track.stableId
                                dropTargetTrackId = track.stableId
                                dragOffsetY = 0f
                            },
                            onDrag = { amount ->
                                dragOffsetY += amount
                                val visibleItems = listState.layoutInfo.visibleItemsInfo
                                val dragged = visibleItems.firstOrNull { it.key == track.stableId }
                                if (dragged != null) {
                                    val center = dragged.offset + dragOffsetY + dragged.size / 2f
                                    val target = visibleItems.firstOrNull { info ->
                                        info.key != track.stableId && center >= info.offset && center < info.offset + info.size
                                    }?.key as? String
                                    if (target != null) {
                                        dropTargetTrackId = target
                                    }
                                    val viewportStart = listState.layoutInfo.viewportStartOffset
                                    val viewportEnd = listState.layoutInfo.viewportEndOffset
                                    if (center < viewportStart + 52f) listState.dispatchRawDelta(-18f)
                                    else if (center > viewportEnd - 52f) listState.dispatchRawDelta(18f)
                                }
                            },
                            onDragEnd = {
                                val currentQueue = viewModel.playbackState.value.queue
                                val from = currentQueue.indexOfFirst { it.stableId == draggedTrackId }
                                val to = currentQueue.indexOfFirst { it.stableId == dropTargetTrackId }
                                if (from >= 0 && to >= 0 && from != to) viewModel.moveQueueItem(from, to)
                                draggedTrackId = null
                                dropTargetTrackId = null
                                dragOffsetY = 0f
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    if (editMode) "Arrastra para cambiar el orden" else "Toca una canción para reproducirla",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    track: AudioTrack,
    isCurrent: Boolean,
    editing: Boolean,
    isDropTarget: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val dragState = rememberDraggableState { delta -> currentOnDrag(delta) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(if (isCurrent) Color(0x2EFF1738) else Color.Transparent)
            .border(
                width = if (isCurrent || isDropTarget) 1.dp else 0.dp,
                color = when {
                    isCurrent -> Color(0xBFFF2948)
                    isDropTarget -> Color(0x66FF2948)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(15.dp)
            )
            .graphicsLayer {
                translationY = dragOffsetY
                scaleX = if (dragOffsetY != 0f) 1.015f else 1f
                scaleY = if (dragOffsetY != 0f) 1.015f else 1f
            }
            .zIndex(if (dragOffsetY != 0f) 1f else 0f)
            .clickable(enabled = !editing, onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isCurrent) {
            Box(Modifier.padding(start = 7.dp).width(3.dp).height(42.dp).clip(RoundedCornerShape(2.dp)).background(AccentRed))
            Spacer(Modifier.width(5.dp))
        }
        if (editing) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.RemoveCircle, "Quitar de la cola", tint = AccentRed, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(6.dp))
        }

        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF343438)),
            contentAlignment = Alignment.Center
        ) {
            if (!track.albumArtUri.isNullOrBlank()) {
                AsyncImage(track.albumArtUri, track.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, null, tint = TextSecondary, modifier = Modifier.size(23.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(track.title, color = if (isCurrent) Color(0xFFFF3653) else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isCurrent) {
                    Text(
                        "AHORA",
                        color = Color(0xFFFF5168),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = .35.sp,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0x443F1019)).padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            Text(track.artist, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AudioQualityBadge(track, compact = true)
        if (editing) {
            Box(
                Modifier
                    .padding(start = 3.dp)
                    .size(48.dp)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        enabled = editing,
                        startDragImmediately = true,
                        onDragStarted = { currentOnDragStart() },
                        onDragStopped = { currentOnDragEnd() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.DragHandle, "Arrastrar para reordenar", tint = if (editing) TextPrimary else TextSecondary, modifier = Modifier.size(25.dp))
            }
        } else {
            Text(track.formattedDuration, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (!editing) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .06f)))
    }
}
