package com.epicenter.hifi.engine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.epicenter.hifi.data.model.AudioTrack
import com.epicenter.hifi.data.model.DspParams
import com.epicenter.hifi.data.model.EqParams
import com.epicenter.hifi.nativeaudio.NativeAudioTrack
import com.epicenter.hifi.nativeaudio.NativePlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatMode {
    OFF, ALL, ONE
}

data class AudioPlaybackState(
    val currentTrack: AudioTrack? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<AudioTrack> = emptyList(),
    val queueIndex: Int = -1,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF
)

class AudioEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(AudioPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    private val _dspParams = MutableStateFlow(DspParams())
    val dspParams: StateFlow<DspParams> = _dspParams.asStateFlow()

    private val _eqParams = MutableStateFlow(EqParams())
    val eqParams: StateFlow<EqParams> = _eqParams.asStateFlow()

    private val _spectrumBands = MutableStateFlow(FloatArray(12) { -100f })
    val spectrumBands: StateFlow<FloatArray> = _spectrumBands.asStateFlow()

    private var controller: NativePlaybackController

    init {
        lateinit var localController: NativePlaybackController
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    startProgressTracking()
                } else {
                    stopProgressTracking()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    _playbackState.value = _playbackState.value.copy(
                        durationMs = localController.duration.coerceAtLeast(0L),
                        currentPositionMs = localController.position.coerceAtLeast(0L)
                    )
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = localController.currentIndex
                val currentQueue = _playbackState.value.queue
                if (index in currentQueue.indices) {
                    _playbackState.value = _playbackState.value.copy(
                        currentTrack = currentQueue[index],
                        queueIndex = index,
                        durationMs = currentQueue[index].durationMs,
                        currentPositionMs = 0L
                    )
                }
            }
        }

        localController = NativePlaybackController(context, listener)
        controller = localController

        // Inicializar DSP por defecto
        applyDspParams(_dspParams.value)
        applyEqParams(_eqParams.value)
        controller.epicenterAudioProcessor.isSpectrumAnalysisEnabled = true
    }

    fun playTrack(track: AudioTrack) {
        val currentQueue = _playbackState.value.queue
        val existingIndex = currentQueue.indexOfFirst { it.stableId == track.stableId }

        if (existingIndex >= 0) {
            playTrackAtIndex(existingIndex)
        } else {
            val newQueue = currentQueue + track
            setQueue(newQueue, newQueue.size - 1)
        }
    }

    fun setQueue(tracks: List<AudioTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val clampedIndex = startIndex.coerceIn(0, tracks.size - 1)
        val nativeTracks = tracks.map { it.toNativeAudioTrack() }

        _playbackState.value = _playbackState.value.copy(
            queue = tracks,
            queueIndex = clampedIndex,
            currentTrack = tracks[clampedIndex],
            durationMs = tracks[clampedIndex].durationMs,
            currentPositionMs = 0L
        )

        controller.setQueue(nativeTracks, clampedIndex)
        controller.play()
    }

    fun playTrackAtIndex(index: Int) {
        val currentQueue = _playbackState.value.queue
        if (index in currentQueue.indices) {
            _playbackState.value = _playbackState.value.copy(
                queueIndex = index,
                currentTrack = currentQueue[index],
                durationMs = currentQueue[index].durationMs,
                currentPositionMs = 0L
            )
            controller.skipToIndex(index)
            controller.play()
        }
    }

    fun togglePlayPause() {
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun next() {
        controller.nextTrack()
    }

    fun previous() {
        if (controller.position > 3000L) {
            controller.seekTo(0L)
        } else {
            controller.previousTrack()
        }
    }

    fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
    }

    fun setShuffle(enabled: Boolean) {
        _playbackState.value = _playbackState.value.copy(isShuffle = enabled)
        controller.player.shuffleModeEnabled = enabled
    }

    fun cycleRepeatMode() {
        val nextMode = when (_playbackState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
        controller.player.repeatMode = when (nextMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    // DSP Controls
    fun setEpicenterEnabled(enabled: Boolean) {
        _dspParams.value = _dspParams.value.copy(enabled = enabled)
        controller.epicenterAudioProcessor.setEpicenterEnabled(enabled)
    }

    fun setDspParams(params: DspParams) {
        _dspParams.value = params
        applyDspParams(params)
    }

    fun updateSweep(sweep: Float) {
        val updated = _dspParams.value.copy(sweepFreq = sweep.coerceIn(27f, 63f))
        setDspParams(updated)
    }

    fun updateWidth(width: Float) {
        val updated = _dspParams.value.copy(width = width.coerceIn(0f, 100f))
        setDspParams(updated)
    }

    fun updateIntensity(intensity: Float) {
        val updated = _dspParams.value.copy(intensity = intensity.coerceIn(0f, 100f))
        setDspParams(updated)
    }

    fun updateBalance(balance: Float) {
        val updated = _dspParams.value.copy(balance = balance.coerceIn(0f, 100f))
        setDspParams(updated)
    }

    fun updateVolume(volume: Float) {
        val updated = _dspParams.value.copy(volume = volume.coerceIn(0f, 100f))
        setDspParams(updated)
    }

    fun setEpicenterMode(mode: String) {
        val isHeadphones = mode.equals("headphones", ignoreCase = true)
        _dspParams.value = _dspParams.value.copy(mode = mode)
        controller.epicenterAudioProcessor.setEpicenterMode(isHeadphones)
    }

    private fun applyDspParams(p: DspParams) {
        controller.epicenterAudioProcessor.setEpicenterEnabled(p.enabled)
        controller.epicenterAudioProcessor.setEpicenterParams(
            p.intensity,
            p.sweepFreq,
            p.width,
            p.balance,
            p.volume
        )
        controller.epicenterAudioProcessor.setEpicenterMode(p.mode.equals("headphones", true))
    }

    // EQ Controls
    fun setEqEnabled(enabled: Boolean) {
        _eqParams.value = _eqParams.value.copy(enabled = enabled)
        controller.eqAudioProcessor.isEnabled = enabled
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 31) {
            val newBands = _eqParams.value.bands.clone()
            newBands[bandIndex] = gainDb.coerceIn(-12f, 12f)
            _eqParams.value = _eqParams.value.copy(bands = newBands)
            controller.eqAudioProcessor.setBandGain(bandIndex, gainDb)
        }
    }

    fun setPreamp(preampDb: Float) {
        val clamped = preampDb.coerceIn(-12f, 12f)
        _eqParams.value = _eqParams.value.copy(preampDb = clamped)
        controller.eqAudioProcessor.setPreampDb(clamped)
    }

    fun resetEq() {
        val zeroBands = FloatArray(31) { 0f }
        _eqParams.value = _eqParams.value.copy(bands = zeroBands, preampDb = 0f)
        for (i in 0 until 31) {
            controller.eqAudioProcessor.setBandGain(i, 0f)
        }
        controller.eqAudioProcessor.setPreampDb(0f)
    }

    private fun applyEqParams(eq: EqParams) {
        controller.eqAudioProcessor.isEnabled = eq.enabled
        controller.eqAudioProcessor.setPreampDb(eq.preampDb)
        eq.bands.forEachIndexed { i, gain ->
            controller.eqAudioProcessor.setBandGain(i, gain)
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (controller.isPlaying) {
                    val pos = controller.position.coerceAtLeast(0L)
                    val dur = controller.duration.coerceAtLeast(0L)
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = pos,
                        durationMs = if (dur > 0L) dur else _playbackState.value.durationMs
                    )

                    // Actualizar espectro
                    val bands = controller.epicenterAudioProcessor.spectrumAnalyzer.bandsDb
                    if (bands != null) {
                        _spectrumBands.value = bands
                    }
                }
                delay(80)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressTracking()
        controller.release()
    }
}

private fun AudioTrack.toNativeAudioTrack(): NativeAudioTrack {
    return NativeAudioTrack(
        this.stableId,
        this.title,
        this.artist,
        this.album,
        this.durationMs,
        this.uri,
        this.albumArtUri ?: ""
    )
}
