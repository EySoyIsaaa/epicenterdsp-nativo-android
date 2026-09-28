package com.epicenter.hifi.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.epicenter.hifi.AppDatabase
import com.epicenter.hifi.TrackEntity
import com.epicenter.hifi.data.model.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val trackDao = db.trackDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val tracks: StateFlow<List<AudioTrack>> = _tracks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    init {
        loadCachedTracks()
    }

    fun loadCachedTracks() {
        scope.launch {
            val entities = trackDao.all ?: emptyList()
            val mapped = entities.map { it.toAudioTrack() }
            _tracks.value = mapped
        }
    }

    suspend fun scanMediaStore(): List<AudioTrack> = withContext(Dispatchers.IO) {
        _isScanning.value = true
        _scanProgress.value = 0f
        val newTracks = mutableListOf<AudioTrack>()
        val entitiesToUpsert = mutableListOf<TrackEntity>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                val total = cursor.count
                var current = 0

                while (cursor.moveToNext()) {
                    val mediaStoreId = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Title"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val album = cursor.getString(albumCol) ?: "Unknown Album"
                    val durationMs = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val dateMod = cursor.getLong(dateModCol)
                    val mimeType = cursor.getString(mimeCol) ?: "audio/mpeg"
                    val albumId = cursor.getLong(albumIdCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        mediaStoreId
                    ).toString()

                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()

                    val isHiRes = mimeType.equals("audio/flac", ignoreCase = true) ||
                            mimeType.equals("audio/x-wav", ignoreCase = true) ||
                            mimeType.equals("audio/wav", ignoreCase = true) ||
                            mimeType.equals("audio/x-flac", ignoreCase = true)

                    val stableId = "ms-$mediaStoreId"

                    val entity = TrackEntity().apply {
                        this.stableId = stableId
                        this.mediaStoreId = mediaStoreId.toString()
                        this.albumId = albumId
                        this.title = title
                        this.artist = artist
                        this.album = album
                        this.duration = durationMs
                        this.sourceUri = contentUri
                        this.size = size
                        this.dateModified = dateMod
                        this.createdAt = dateAdded * 1000L
                        this.mimeType = mimeType
                        this.isHiRes = isHiRes
                        this.updatedAt = System.currentTimeMillis()
                    }
                    entitiesToUpsert.add(entity)

                    val audioTrack = AudioTrack(
                        id = stableId,
                        stableId = stableId,
                        title = title,
                        artist = if (artist.equals("<unknown>", true)) "Unknown Artist" else artist,
                        album = if (album.equals("<unknown>", true)) "Unknown Album" else album,
                        duration = durationMs / 1000.0,
                        uri = contentUri,
                        isHiRes = isHiRes,
                        albumArtUri = albumArtUri,
                        size = size,
                        dateAdded = dateAdded
                    )
                    newTracks.add(audioTrack)

                    current++
                    if (total > 0 && current % 50 == 0) {
                        _scanProgress.value = current.toFloat() / total.toFloat()
                    }
                }
            }

            if (entitiesToUpsert.isNotEmpty()) {
                trackDao.upsertAll(entitiesToUpsert)
            }
            _tracks.value = newTracks
            _scanProgress.value = 1f
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error scanning media store", e)
        } finally {
            _isScanning.value = false
        }
        newTracks
    }

    suspend fun enrichTrackMetadata(track: AudioTrack): AudioTrack = withContext(Dispatchers.IO) {
        if (track.sampleRate != null && track.sampleRate > 0) return@withContext track
        val contentUri = Uri.parse(track.uri)
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var sampleRate: Int? = null
        var bitDepth: Int? = null
        var bitrate: Int? = null
        var channels: Int? = null

        try {
            retriever.setDataSource(context, contentUri)
            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            bitrate = bitrateStr?.toIntOrNull()
        } catch (_: Exception) {}

        try {
            extractor.setDataSource(context, contentUri, null)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (!mime.startsWith("audio/")) continue
                if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (bitrate == null && fmt.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = fmt.getInteger(MediaFormat.KEY_BIT_RATE)
                if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (fmt.containsKey("bits-per-sample")) bitDepth = fmt.getInteger("bits-per-sample")
                break
            }
        } catch (_: Exception) {}
        finally {
            try { retriever.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        val isHiRes = track.isHiRes || (bitDepth != null && bitDepth >= 24) || (sampleRate != null && sampleRate >= 48000)

        trackDao.updateFormatInfo(
            track.stableId,
            bitDepth,
            sampleRate,
            bitrate,
            channels,
            isHiRes,
            System.currentTimeMillis()
        )

        val updated = track.copy(
            bitDepth = bitDepth,
            sampleRate = sampleRate,
            bitrate = bitrate,
            channels = channels,
            isHiRes = isHiRes
        )

        _tracks.value = _tracks.value.map { if (it.stableId == track.stableId) updated else it }
        updated
    }
}

private fun TrackEntity.toAudioTrack(): AudioTrack {
    val albumArt = if (!this.mediaStoreId.isNullOrEmpty()) {
        try {
            ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                this.mediaStoreId.toLong()
            ).toString()
        } catch (_: Exception) {
            null
        }
    } else null

    val trackStableId = if (this.stableId.isNotEmpty()) this.stableId else "track-${this.id}"

    return AudioTrack(
        id = trackStableId,
        stableId = trackStableId,
        title = this.title ?: "Unknown Title",
        artist = this.artist ?: "Unknown Artist",
        album = this.album ?: "Unknown Album",
        duration = this.duration / 1000.0,
        uri = this.sourceUri ?: "",
        bitDepth = this.bitDepth,
        sampleRate = this.sampleRate,
        bitrate = this.bitrate,
        channels = this.channels,
        isHiRes = this.isHiRes ?: false,
        albumArtUri = albumArt,
        size = this.size,
        dateAdded = this.createdAt
    )
}
