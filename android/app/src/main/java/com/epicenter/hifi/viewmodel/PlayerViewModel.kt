package com.epicenter.hifi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.epicenter.hifi.data.model.AudioTrack
import com.epicenter.hifi.engine.AudioEngine
import com.epicenter.hifi.engine.AudioPlaybackState
import com.epicenter.hifi.engine.RepeatMode
import kotlinx.coroutines.flow.StateFlow

class PlayerViewModel(private val audioEngine: AudioEngine) : ViewModel() {

    val playbackState: StateFlow<AudioPlaybackState> = audioEngine.playbackState

    fun playTrack(track: AudioTrack) {
        audioEngine.playTrack(track)
    }

    fun playQueue(tracks: List<AudioTrack>, startIndex: Int = 0) {
        audioEngine.setQueue(tracks, startIndex)
    }

    fun togglePlayPause() {
        audioEngine.togglePlayPause()
    }

    fun next() {
        audioEngine.next()
    }

    fun previous() {
        audioEngine.previous()
    }

    fun seekTo(positionMs: Long) {
        audioEngine.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val current = playbackState.value.isShuffle
        audioEngine.setShuffle(!current)
    }

    fun cycleRepeatMode() {
        audioEngine.cycleRepeatMode()
    }

    fun playTrackAtIndex(index: Int) {
        audioEngine.playTrackAtIndex(index)
    }

    class Factory(private val audioEngine: AudioEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(audioEngine) as T
        }
    }
}
