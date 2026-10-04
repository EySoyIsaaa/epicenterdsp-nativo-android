package com.epicenter.hifi.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.epicenter.hifi.data.model.AudioTrack
import com.epicenter.hifi.data.repository.ImportTracksResult
import com.epicenter.hifi.data.repository.LocalPlaylist
import com.epicenter.hifi.data.repository.MusicRepository
import com.epicenter.hifi.engine.AudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab {
    SONGS, ARTISTS, ALBUMS, PLAYLISTS
}

enum class SortMode {
    DEFAULT, TITLE, ARTIST
}

data class ArtistGroup(
    val name: String,
    val tracks: List<AudioTrack>
)

data class AlbumGroup(
    val name: String,
    val artist: String,
    val albumArtUri: String?,
    val tracks: List<AudioTrack>
)

class LibraryViewModel(
    private val musicRepository: MusicRepository,
    private val audioEngine: AudioEngine
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(LibraryTab.SONGS)
    val selectedTab: StateFlow<LibraryTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.DEFAULT)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    val isScanning: StateFlow<Boolean> = musicRepository.isScanning
    val scanProgress: StateFlow<Float> = musicRepository.scanProgress
    val playlists: StateFlow<List<LocalPlaylist>> = musicRepository.playlists
    val allTracks: StateFlow<List<AudioTrack>> = musicRepository.tracks

    private val _selectedPlaylistTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val selectedPlaylistTracks: StateFlow<List<AudioTrack>> = _selectedPlaylistTracks.asStateFlow()

    val filteredTracks: StateFlow<List<AudioTrack>> = combine(
        musicRepository.tracks,
        _searchQuery,
        _sortMode
    ) { tracks, query, sort ->
        val filtered = if (query.isBlank()) {
            tracks
        } else {
            tracks.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true) ||
                        it.album.contains(query, ignoreCase = true)
            }
        }

        when (sort) {
            SortMode.DEFAULT -> filtered
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artistGroups: StateFlow<List<ArtistGroup>> = filteredTracks.combine(_searchQuery) { tracks, _ ->
        tracks.groupBy { it.artist }
            .map { (artist, trackList) -> ArtistGroup(artist, trackList) }
            .sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albumGroups: StateFlow<List<AlbumGroup>> = filteredTracks.combine(_searchQuery) { tracks, _ ->
        tracks.groupBy { "${it.album}||${it.artist}" }
            .map { (_, trackList) ->
                val first = trackList.first()
                AlbumGroup(
                    name = first.album,
                    artist = first.artist,
                    albumArtUri = first.albumArtUri,
                    tracks = trackList
                )
            }
            .sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSelectedTab(tab: LibraryTab) {
        _selectedTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun triggerScan() {
        viewModelScope.launch {
            musicRepository.scanMediaStore()
        }
    }

    fun playTrack(track: AudioTrack) {
        val tracks = filteredTracks.value
        val index = tracks.indexOfFirst { it.stableId == track.stableId }
        if (index >= 0) {
            audioEngine.setQueue(tracks, index)
        } else {
            audioEngine.playTrack(track)
        }
    }

    fun playAll(tracks: List<AudioTrack>, startIndex: Int = 0) {
        audioEngine.setQueue(tracks, startIndex)
    }

    fun enrichTrackMetadata(track: AudioTrack) {
        viewModelScope.launch {
            musicRepository.enrichTrackMetadata(track)
        }
    }

    fun importUris(uris: List<Uri>, onComplete: (ImportTracksResult) -> Unit = {}) {
        viewModelScope.launch {
            onComplete(musicRepository.importUris(uris))
        }
    }

    fun createPlaylist(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch { onCreated(musicRepository.createPlaylist(name)) }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch { musicRepository.renamePlaylist(id, name) }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch { musicRepository.deletePlaylist(id) }
    }

    fun loadPlaylist(id: String) {
        viewModelScope.launch { _selectedPlaylistTracks.value = musicRepository.getPlaylistTracks(id) }
    }

    fun addTrackToPlaylist(id: String, track: AudioTrack) {
        viewModelScope.launch { musicRepository.addTracksToPlaylist(id, listOf(track)) }
    }

    fun addTracksToPlaylist(id: String, tracks: List<AudioTrack>) {
        viewModelScope.launch {
            musicRepository.addTracksToPlaylist(id, tracks)
            if (_selectedPlaylistTracks.value.isNotEmpty()) {
                _selectedPlaylistTracks.value = musicRepository.getPlaylistTracks(id)
            }
        }
    }

    fun removeTrackFromPlaylist(id: String, track: AudioTrack) {
        viewModelScope.launch {
            musicRepository.removeTrackFromPlaylist(id, track.stableId)
            _selectedPlaylistTracks.value = musicRepository.getPlaylistTracks(id)
        }
    }

    fun removeFromLibrary(track: AudioTrack) {
        viewModelScope.launch { musicRepository.removeFromLibrary(track) }
    }

    fun playNext(track: AudioTrack) {
        audioEngine.addToQueue(track, playNext = true)
    }

    fun addToQueue(track: AudioTrack) {
        audioEngine.addToQueue(track, playNext = false)
    }

    class Factory(
        private val musicRepository: MusicRepository,
        private val audioEngine: AudioEngine
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(musicRepository, audioEngine) as T
        }
    }
}
