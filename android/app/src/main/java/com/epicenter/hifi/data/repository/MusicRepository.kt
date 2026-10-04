package com.epicenter.hifi.data.repository

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.epicenter.hifi.AppDatabase
import com.epicenter.hifi.PlaylistEntity
import com.epicenter.hifi.PlaylistTrackEntity
import com.epicenter.hifi.TrackEntity
import com.epicenter.hifi.data.model.AudioTrack
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

class MusicRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val trackDao = db.trackDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyPreferences = context.applicationContext
        .getSharedPreferences("epicenter_listening_history", Context.MODE_PRIVATE)
    private val _recentTrackIds = MutableStateFlow(readRecentTrackIds())
    val recentTrackIds: StateFlow<List<String>> = _recentTrackIds.asStateFlow()
    private val metadataEnrichmentStarted = ConcurrentHashMap.newKeySet<String>()

    private val _tracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val tracks: StateFlow<List<AudioTrack>> = _tracks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    init {
        loadCachedTracks()
        refreshPlaylists()
    }

    private fun readRecentTrackIds(): List<String> = runCatching {
        val json = historyPreferences.getString("recent_track_ids", "[]") ?: "[]"
        JSONArray(json).let { array -> List(array.length()) { array.getString(it) } }.distinct().take(40)
    }.getOrDefault(emptyList())

    fun recordRecentlyPlayed(track: AudioTrack) {
        val next = listOf(track.stableId) + _recentTrackIds.value.filterNot { it == track.stableId }
        val bounded = next.take(40)
        _recentTrackIds.value = bounded
        historyPreferences.edit().putString("recent_track_ids", JSONArray(bounded).toString()).apply()
    }

    fun refreshPlaylists() {
        scope.launch {
            _playlists.value = withContext(Dispatchers.IO) {
                db.playlistDao().getAll().map { playlist ->
                    LocalPlaylist(
                        id = playlist.playlistId,
                        name = playlist.name,
                        trackIds = db.playlistDao().getTrackIds(playlist.playlistId)
                    )
                }
            }
        }
    }

    suspend fun createPlaylist(name: String): String = withContext(Dispatchers.IO) {
        val cleanName = name.trim().ifEmpty { "Nueva playlist" }
        val now = System.currentTimeMillis()
        val id = "playlist-${java.util.UUID.randomUUID()}"
        db.playlistDao().upsert(PlaylistEntity().apply {
            playlistId = id
            this.name = cleanName
            createdAt = now
            updatedAt = now
        })
        refreshPlaylists()
        id
    }

    suspend fun renamePlaylist(id: String, name: String) = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        if (cleanName.isNotEmpty()) db.playlistDao().rename(id, cleanName, System.currentTimeMillis())
        refreshPlaylists()
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        db.playlistDao().deletePlaylist(id)
        refreshPlaylists()
    }

    suspend fun getPlaylistTracks(id: String): List<AudioTrack> = withContext(Dispatchers.IO) {
        db.playlistDao().getTrackIds(id).mapNotNull { trackDao.getByStableId(it)?.toAudioTrack() }
    }

    suspend fun addTracksToPlaylist(id: String, tracks: List<AudioTrack>) = withContext(Dispatchers.IO) {
        var position = (db.playlistDao().getMaxPosition(id) ?: -1) + 1
        tracks.forEach { track ->
            db.playlistDao().insertTrack(PlaylistTrackEntity().apply {
                playlistId = id
                trackStableId = track.stableId
                this.position = position++
                addedAt = System.currentTimeMillis()
            })
        }
        db.playlistDao().touch(id, System.currentTimeMillis())
        refreshPlaylists()
    }

    suspend fun removeTrackFromPlaylist(id: String, trackId: String) = withContext(Dispatchers.IO) {
        db.playlistDao().removeTrack(id, trackId)
        refreshPlaylists()
    }

    suspend fun importUris(uris: List<Uri>): ImportTracksResult = withContext(Dispatchers.IO) {
        val imported = mutableListOf<AudioTrack>()
        var duplicates = 0
        uris.forEach { uri ->
            try {
                val uriText = uri.toString()
                if (trackDao.getBySourceUri(uriText) != null) {
                    duplicates++
                    return@forEach
                }

                val retriever = MediaMetadataRetriever()
                val title: String
                val artist: String
                val album: String
                val duration: Long
                val bitrate: Int?
                var bitDepth: Int? = null
                val embeddedArtwork: ByteArray?
                try {
                    retriever.setDataSource(context, uri)
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio importado"
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artista desconocido"
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Álbum desconocido"
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bitDepth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                    }
                    embeddedArtwork = retriever.embeddedPicture
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
                val id = "doc-${sha256(uriText).take(24)}"
                val artwork = embeddedArtwork?.let { bytes -> saveArtwork(id, bytes) }

                var sampleRate: Int? = null
                var channels: Int? = null
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        if (!(format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) continue
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey("bits-per-sample")) bitDepth = format.getInteger("bits-per-sample")
                        break
                    }
                } catch (_: Exception) {
                } finally {
                    try { extractor.release() } catch (_: Exception) {}
                }

                val hiRes = (bitDepth ?: 0) >= 24 && (sampleRate ?: 0) >= 44_100
                trackDao.upsert(TrackEntity().apply {
                    stableId = id
                    sourceUri = uriText
                    localUri = uriText
                    this.title = title
                    this.artist = artist
                    this.album = album
                    this.duration = duration
                    this.bitrate = bitrate
                    this.sampleRate = sampleRate
                    this.channels = channels
                    this.bitDepth = bitDepth
                    isHiRes = hiRes
                    albumArtUri = artwork
                    sourceType = "document"
                    mimeType = context.contentResolver.getType(uri)
                    size = 0L
                    createdAt = System.currentTimeMillis()
                    updatedAt = System.currentTimeMillis()
                })
                imported += AudioTrack(
                    id = id, stableId = id, title = title, artist = artist, album = album,
                    duration = duration / 1000.0, uri = uriText, bitDepth = bitDepth,
                    sampleRate = sampleRate, bitrate = bitrate, channels = channels,
                    isHiRes = hiRes, albumArtUri = artwork
                )
            } catch (error: Exception) {
                Log.w("MusicRepository", "No se pudo importar $uri", error)
            }
        }
        loadCachedTracks()
        ImportTracksResult(imported, duplicates)
    }

    suspend fun removeFromLibrary(track: AudioTrack) = withContext(Dispatchers.IO) {
        trackDao.deleteByStableId(track.stableId)
        db.playlistDao().removeTrackEverywhere(track.stableId)
        _tracks.value = _tracks.value.filterNot { it.stableId == track.stableId }
        refreshPlaylists()
    }

    private fun saveArtwork(trackId: String, bytes: ByteArray): String? = try {
        val directory = File(context.filesDir, "album-art").apply { mkdirs() }
        val file = File(directory, "$trackId.jpg")
        file.writeBytes(bytes)
        Uri.fromFile(file).toString()
    } catch (_: Exception) {
        null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

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
        val cachedTracks = _tracks.value.associateBy(AudioTrack::stableId)

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

                    val stableId = "ms-$mediaStoreId"
                    val cached = cachedTracks[stableId]
                    val bitDepth = cached?.bitDepth
                    val sampleRate = cached?.sampleRate
                    val bitrate = cached?.bitrate
                    val channels = cached?.channels
                    val isHiRes = (bitDepth ?: 0) >= 24 && (sampleRate ?: 0) >= 44_100

                    val entity = TrackEntity().apply {
                        this.stableId = stableId
                        this.mediaStoreId = mediaStoreId.toString()
                        this.albumId = albumId
                        this.title = title
                        this.artist = artist
                        this.album = album
                        this.duration = durationMs
                        this.bitDepth = bitDepth
                        this.sampleRate = sampleRate
                        this.bitrate = bitrate
                        this.channels = channels
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
                        bitDepth = bitDepth,
                        sampleRate = sampleRate,
                        bitrate = bitrate,
                        channels = channels,
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
            scope.launch {
                newTracks.chunked(4).forEach { batch ->
                    coroutineScope { batch.map { track -> async { enrichTrackMetadata(track) } }.awaitAll() }
                }
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error scanning media store", e)
        } finally {
            _isScanning.value = false
        }
        newTracks
    }

    suspend fun enrichTrackMetadata(track: AudioTrack): AudioTrack = withContext(Dispatchers.IO) {
        if ((track.sampleRate ?: 0) > 0 && (track.bitDepth ?: 0) > 0 && (track.bitrate ?: 0) > 0) return@withContext track
        if (!metadataEnrichmentStarted.add(track.stableId)) return@withContext track
        val contentUri = Uri.parse(track.uri)
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var sampleRate: Int? = track.sampleRate
        var bitDepth: Int? = track.bitDepth
        var bitrate: Int? = track.bitrate
        var channels: Int? = track.channels

        try {
            retriever.setDataSource(context, contentUri)
            if (bitrate == null) bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            if (bitDepth == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bitDepth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
            }
        } catch (_: Exception) {}

        try {
            extractor.setDataSource(context, contentUri, null)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (!mime.startsWith("audio/")) continue
                if (sampleRate == null && fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (bitrate == null && fmt.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = fmt.getInteger(MediaFormat.KEY_BIT_RATE)
                if (channels == null && fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (bitDepth == null && fmt.containsKey("bits-per-sample")) bitDepth = fmt.getInteger("bits-per-sample")
                break
            }
        } catch (_: Exception) {}
        finally {
            try { retriever.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        val isHiRes = (bitDepth ?: 0) >= 24 && (sampleRate ?: 0) >= 44_100

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

        synchronized(_tracks) {
            _tracks.value = _tracks.value.map { if (it.stableId == track.stableId) updated else it }
        }
        updated
    }
}

private fun TrackEntity.toAudioTrack(): AudioTrack {
    val albumArt = this.albumArtUri ?: if (!this.mediaStoreId.isNullOrEmpty()) {
        try {
            ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                this.albumId ?: this.mediaStoreId.toLong()
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

data class LocalPlaylist(val id: String, val name: String, val trackIds: List<String>)
data class ImportTracksResult(val tracks: List<AudioTrack>, val duplicateCount: Int)
