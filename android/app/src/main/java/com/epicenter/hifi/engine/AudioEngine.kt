package com.epicenter.hifi.engine

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
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

class AudioEngine(context: Context, private val controller: NativePlaybackController) {

    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences("epicenter_audio_preferences", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(readPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    private val _dspParams = MutableStateFlow(readDspParams())
    val dspParams: StateFlow<DspParams> = _dspParams.asStateFlow()

    private val _eqParams = MutableStateFlow(readEqParams())
    val eqParams: StateFlow<EqParams> = _eqParams.asStateFlow()

    private val _spatialEffects = MutableStateFlow(readSpatialEffects())
    val spatialEffects: StateFlow<SpatialEffectsParams> = _spatialEffects.asStateFlow()

    private val _crossfade = MutableStateFlow(
        CrossfadeSettings(
            enabled = preferences.getBoolean("crossfade.enabled", false),
            durationSeconds = preferences.getInt("crossfade.duration", 5).coerceIn(3, 10)
        )
    )
    val crossfade: StateFlow<CrossfadeSettings> = _crossfade.asStateFlow()

    private val _spectrumBands = MutableStateFlow(FloatArray(12) { -100f })
    val spectrumBands: StateFlow<FloatArray> = _spectrumBands.asStateFlow()

    private val playerListener = object : Player.Listener {
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
                        durationMs = controller.duration.coerceAtLeast(0L),
                        currentPositionMs = controller.position.coerceAtLeast(0L)
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // NativePlaybackController will try the next healthy queue item.
                // Clear the stale playing indicator while that recovery runs.
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    currentPositionMs = controller.position.coerceAtLeast(0L)
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = controller.currentIndex
                val previousQueue = _playbackState.value.queue.associateBy { it.stableId }
                val currentQueue = controller.getQueue().map { nativeTrack ->
                    val fresh = nativeTrack.toAudioTrack()
                    previousQueue[fresh.stableId]?.copy(
                        title = fresh.title,
                        artist = fresh.artist,
                        album = fresh.album,
                        duration = fresh.duration,
                        uri = fresh.uri,
                        albumArtUri = fresh.albumArtUri
                    ) ?: fresh
                }
                if (index in currentQueue.indices) {
                    _playbackState.value = _playbackState.value.copy(
                        queue = currentQueue,
                        currentTrack = currentQueue[index],
                        queueIndex = index,
                        durationMs = currentQueue[index].durationMs,
                        currentPositionMs = 0L
                    )
                }
            }
        }

    init {
        controller.addPlayerListener(playerListener)
        applyDspParams(_dspParams.value)
        applyEqParams(_eqParams.value)
        applySpatialEffects(_spatialEffects.value)
        controller.setCrossfadeConfig(_crossfade.value.enabled, _crossfade.value.durationSeconds * 1000L)
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
        if (tracks.isEmpty()) {
            clearQueue()
            return
        }
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

    fun updateTrackMetadata(track: AudioTrack) {
        val current = _playbackState.value
        if (current.queue.none { it.stableId == track.stableId }) return
        val updatedQueue = current.queue.map { queued ->
            if (queued.stableId == track.stableId) track else queued
        }
        _playbackState.value = current.copy(
            queue = updatedQueue,
            currentTrack = if (current.currentTrack?.stableId == track.stableId) track else current.currentTrack
        )
    }

    fun addToQueue(track: AudioTrack, playNext: Boolean) {
        val current = _playbackState.value
        if (current.queue.isEmpty()) {
            setQueue(listOf(track), 0)
            return
        }
        if (current.queue.any { it.stableId == track.stableId }) return
        val insertionIndex = if (playNext) (current.queueIndex + 1).coerceIn(0, current.queue.size) else current.queue.size
        val nextQueue = current.queue.toMutableList().apply { add(insertionIndex, track) }
        controller.addTrack(track.toNativeAudioTrack(), insertionIndex)
        val nextIndex = current.queueIndex + if (insertionIndex <= current.queueIndex) 1 else 0
        _playbackState.value = current.copy(queue = nextQueue, queueIndex = nextIndex)
    }

    fun removeFromQueue(index: Int) {
        val current = _playbackState.value
        if (index !in current.queue.indices) return
        val removedCurrent = index == current.queueIndex
        val nextQueue = current.queue.toMutableList().apply { removeAt(index) }
        controller.removeTrack(index)
        if (nextQueue.isEmpty()) {
            _playbackState.value = AudioPlaybackState(isShuffle = current.isShuffle, repeatMode = current.repeatMode)
            return
        }
        val nextIndex = when {
            removedCurrent -> index.coerceAtMost(nextQueue.lastIndex)
            index < current.queueIndex -> current.queueIndex - 1
            else -> current.queueIndex
        }
        val nextTrack = nextQueue[nextIndex]
        _playbackState.value = current.copy(
            queue = nextQueue,
            queueIndex = nextIndex,
            currentTrack = nextTrack,
            currentPositionMs = if (removedCurrent) 0L else current.currentPositionMs,
            durationMs = nextTrack.durationMs
        )
    }

    fun moveQueueItem(from: Int, to: Int) {
        val current = _playbackState.value
        if (from !in current.queue.indices || to !in current.queue.indices || from == to) return
        val nextQueue = current.queue.toMutableList().apply { add(to, removeAt(from)) }
        val nextIndex = when {
            current.queueIndex == from -> to
            from < current.queueIndex && to >= current.queueIndex -> current.queueIndex - 1
            from > current.queueIndex && to <= current.queueIndex -> current.queueIndex + 1
            else -> current.queueIndex
        }
        controller.moveTrack(from, to)
        _playbackState.value = current.copy(queue = nextQueue, queueIndex = nextIndex)
    }

    fun clearQueue() {
        controller.clearQueue()
        _playbackState.value = AudioPlaybackState()
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
        persistDspParams(_dspParams.value)
    }

    fun setDspParams(params: DspParams) {
        _dspParams.value = params
        applyDspParams(params)
        persistDspParams(params)
    }

    fun setDspParamsRealtime(params: DspParams) {
        _dspParams.value = params
        controller.epicenterAudioProcessor.setEpicenterParams(
            params.intensity,
            params.sweepFreq,
            params.width,
            params.balance,
            params.volume
        )
    }

    fun persistCurrentDspParams() {
        persistDspParams(_dspParams.value)
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
        persistDspParams(_dspParams.value)
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
        persistEqParams(_eqParams.value)
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        setBandGainRealtime(bandIndex, gainDb)
        persistEqParams(_eqParams.value)
    }

    fun setBandGainRealtime(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 31) {
            val newBands = _eqParams.value.bands.clone()
            newBands[bandIndex] = gainDb.coerceIn(-12f, 12f)
            _eqParams.value = _eqParams.value.copy(bands = newBands)
            controller.eqAudioProcessor.setBandGain(bandIndex, gainDb)
        }
    }

    fun persistCurrentEqParams() = persistEqParams(_eqParams.value)

    fun setPreamp(preampDb: Float) {
        val clamped = preampDb.coerceIn(-12f, 12f)
        _eqParams.value = _eqParams.value.copy(preampDb = clamped)
        controller.eqAudioProcessor.setPreampDb(clamped)
        persistEqParams(_eqParams.value)
    }

    fun resetEq() {
        val zeroBands = FloatArray(31) { 0f }
        _eqParams.value = _eqParams.value.copy(bands = zeroBands, preampDb = 0f)
        controller.eqAudioProcessor.setBandsGain(zeroBands)
        controller.eqAudioProcessor.setPreampDb(0f)
        persistEqParams(_eqParams.value)
    }

    fun setEqBands(gains: FloatArray) {
        val safe = FloatArray(31) { index -> (gains.getOrNull(index) ?: 0f).coerceIn(-12f, 12f) }
        _eqParams.value = _eqParams.value.copy(bands = safe)
        controller.eqAudioProcessor.setBandsGain(safe)
        persistEqParams(_eqParams.value)
    }

    fun setReverbEnabled(enabled: Boolean) {
        _spatialEffects.value = _spatialEffects.value.copy(reverbEnabled = enabled)
        controller.reverbAudioProcessor.setReverbEnabled(enabled)
        persistSpatialEffects(_spatialEffects.value)
    }

    fun setReverbAmount(amount: Float) {
        val safe = amount.coerceIn(0f, 100f)
        _spatialEffects.value = _spatialEffects.value.copy(reverbAmount = safe)
        controller.reverbAudioProcessor.setReverbAmount(safe)
        persistSpatialEffects(_spatialEffects.value)
    }

    fun setReverbAmountRealtime(amount: Float) {
        val safe = amount.coerceIn(0f, 100f)
        _spatialEffects.value = _spatialEffects.value.copy(reverbAmount = safe)
        controller.reverbAudioProcessor.setReverbAmount(safe)
    }

    fun setConcertHallEnabled(enabled: Boolean) {
        _spatialEffects.value = _spatialEffects.value.copy(concertHallEnabled = enabled)
        controller.reverbAudioProcessor.setConcertHallEnabled(enabled)
        persistSpatialEffects(_spatialEffects.value)
    }

    fun setConcertHallAmount(amount: Float) {
        val safe = amount.coerceIn(0f, 100f)
        _spatialEffects.value = _spatialEffects.value.copy(concertHallAmount = safe)
        controller.reverbAudioProcessor.setConcertHallAmount(safe)
        persistSpatialEffects(_spatialEffects.value)
    }

    fun setConcertHallAmountRealtime(amount: Float) {
        val safe = amount.coerceIn(0f, 100f)
        _spatialEffects.value = _spatialEffects.value.copy(concertHallAmount = safe)
        controller.reverbAudioProcessor.setConcertHallAmount(safe)
    }

    fun persistCurrentSpatialEffects() {
        persistSpatialEffects(_spatialEffects.value)
    }

    fun setCrossfade(enabled: Boolean = _crossfade.value.enabled, durationSeconds: Int = _crossfade.value.durationSeconds) {
        val settings = CrossfadeSettings(enabled, durationSeconds.coerceIn(3, 10))
        _crossfade.value = settings
        preferences.edit()
            .putBoolean("crossfade.enabled", settings.enabled)
            .putInt("crossfade.duration", settings.durationSeconds)
            .apply()
        controller.setCrossfadeConfig(settings.enabled, settings.durationSeconds * 1000L)
    }

    private fun applyEqParams(eq: EqParams) {
        controller.eqAudioProcessor.isEnabled = eq.enabled
        controller.eqAudioProcessor.setPreampDb(eq.preampDb)
        controller.eqAudioProcessor.setBandsGain(eq.bands)
    }

    private fun applySpatialEffects(effects: SpatialEffectsParams) {
        controller.reverbAudioProcessor.setReverbAmount(effects.reverbAmount)
        controller.reverbAudioProcessor.setConcertHallAmount(effects.concertHallAmount)
        controller.reverbAudioProcessor.setReverbEnabled(effects.reverbEnabled)
        controller.reverbAudioProcessor.setConcertHallEnabled(effects.concertHallEnabled)
    }

    private fun readDspParams() = DspParams(
        sweepFreq = preferences.getFloat("dsp.sweep", 45f).coerceIn(27f, 63f),
        width = preferences.getFloat("dsp.width", 50f).coerceIn(0f, 100f),
        intensity = preferences.getFloat("dsp.intensity", 100f).coerceIn(0f, 100f),
        balance = preferences.getFloat("dsp.balance", 100f).coerceIn(0f, 100f),
        volume = preferences.getFloat("dsp.volume", 100f).coerceIn(0f, 100f),
        enabled = preferences.getBoolean("dsp.enabled", true),
        mode = preferences.getString("dsp.mode", "car") ?: "car"
    )

    private fun readEqParams(): EqParams {
        val stored = preferences.getString("eq.bands", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        val bands = FloatArray(31) { index -> stored?.getOrNull(index)?.coerceIn(-12f, 12f) ?: 0f }
        return EqParams(
            enabled = preferences.getBoolean("eq.enabled", true),
            bands = bands,
            preampDb = preferences.getFloat("eq.preamp", 0f).coerceIn(-12f, 12f)
        )
    }

    private fun readSpatialEffects() = SpatialEffectsParams(
        reverbEnabled = preferences.getBoolean("fx.reverbEnabled", false),
        reverbAmount = preferences.getFloat("fx.reverbAmount", 35f).coerceIn(0f, 100f),
        concertHallEnabled = preferences.getBoolean("fx.hallEnabled", false),
        concertHallAmount = preferences.getFloat("fx.hallAmount", 45f).coerceIn(0f, 100f)
    )

    private fun persistDspParams(params: DspParams) {
        preferences.edit()
            .putFloat("dsp.sweep", params.sweepFreq)
            .putFloat("dsp.width", params.width)
            .putFloat("dsp.intensity", params.intensity)
            .putFloat("dsp.balance", params.balance)
            .putFloat("dsp.volume", params.volume)
            .putBoolean("dsp.enabled", params.enabled)
            .putString("dsp.mode", params.mode)
            .apply()
    }

    private fun persistEqParams(params: EqParams) {
        preferences.edit()
            .putBoolean("eq.enabled", params.enabled)
            .putFloat("eq.preamp", params.preampDb)
            .putString("eq.bands", params.bands.joinToString(","))
            .apply()
    }

    private fun persistSpatialEffects(effects: SpatialEffectsParams) {
        preferences.edit()
            .putBoolean("fx.reverbEnabled", effects.reverbEnabled)
            .putFloat("fx.reverbAmount", effects.reverbAmount)
            .putBoolean("fx.hallEnabled", effects.concertHallEnabled)
            .putFloat("fx.hallAmount", effects.concertHallAmount)
            .apply()
    }

    private fun readPlaybackState(): AudioPlaybackState {
        val queue = controller.getQueue().map { it.toAudioTrack() }
        val index = controller.currentIndex
        val current = queue.getOrNull(index)
        return AudioPlaybackState(
            currentTrack = current,
            isPlaying = controller.isPlaying,
            currentPositionMs = controller.position.coerceAtLeast(0L),
            durationMs = controller.duration.coerceAtLeast(0L),
            queue = queue,
            queueIndex = index,
            isShuffle = controller.player.shuffleModeEnabled,
            repeatMode = when (controller.player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
        )
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
        controller.removePlayerListener(playerListener)
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

private fun NativeAudioTrack.toAudioTrack() = AudioTrack(
    id = id,
    stableId = id,
    title = title,
    artist = artist,
    album = album,
    duration = duration / 1000.0,
    uri = source,
    albumArtUri = artworkUri.takeIf { it.isNotBlank() }
)

data class SpatialEffectsParams(
    val reverbEnabled: Boolean = false,
    val reverbAmount: Float = 35f,
    val concertHallEnabled: Boolean = false,
    val concertHallAmount: Float = 45f
)

data class CrossfadeSettings(val enabled: Boolean = false, val durationSeconds: Int = 5)
