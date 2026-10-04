package com.epicenter.hifi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.epicenter.hifi.data.model.DspParams
import com.epicenter.hifi.engine.AudioEngine
import kotlinx.coroutines.flow.StateFlow

class DspViewModel(private val audioEngine: AudioEngine) : ViewModel() {

    val dspParams: StateFlow<DspParams> = audioEngine.dspParams

    fun setEpicenterEnabled(enabled: Boolean) {
        audioEngine.setEpicenterEnabled(enabled)
    }

    fun setSweep(sweep: Float) {
        audioEngine.setDspParamsRealtime(dspParams.value.copy(sweepFreq = sweep.coerceIn(27f, 63f)))
    }

    fun setWidth(width: Float) {
        audioEngine.setDspParamsRealtime(dspParams.value.copy(width = width.coerceIn(0f, 100f)))
    }

    fun setIntensity(intensity: Float) {
        audioEngine.setDspParamsRealtime(dspParams.value.copy(intensity = intensity.coerceIn(0f, 100f)))
    }

    fun setBalance(balance: Float) {
        audioEngine.setDspParamsRealtime(dspParams.value.copy(balance = balance.coerceIn(0f, 100f)))
    }

    fun setVolume(volume: Float) {
        audioEngine.setDspParamsRealtime(dspParams.value.copy(volume = volume.coerceIn(0f, 100f)))
    }

    fun persistKnobValues() {
        audioEngine.persistCurrentDspParams()
    }

    fun setMode(mode: String) {
        audioEngine.setEpicenterMode(mode)
    }

    fun autoOptimizeFromSpectrum(): Boolean {
        val current = dspParams.value
        if (current.mode == "headphones") return false
        val bands = audioEngine.spectrumBands.value
        if (bands.size != 12 || bands.all { it <= -100f }) return false
        val edges = floatArrayOf(20f, 40f, 60f, 90f, 125f, 180f, 250f, 400f, 800f, 2000f, 4000f, 8000f, 16000f)
        fun average(loHz: Float, hiHz: Float): Float {
            var weighted = 0f
            var weight = 0f
            for (index in bands.indices) {
                val overlap = minOf(edges[index + 1], hiHz) - maxOf(edges[index], loHz)
                if (overlap <= 0f) continue
                val bandWeight = overlap / (edges[index + 1] - edges[index])
                weighted += bands[index] * bandWeight
                weight += bandWeight
            }
            return if (weight > 0f) weighted / weight else -120f
        }
        fun clamp01(value: Float) = value.coerceIn(0f, 1f)
        val sub = average(20f, 60f)
        val bass = average(60f, 125f)
        val mids = average(250f, 2000f)
        val deficit = mids - sub
        val bodyPenalty = clamp01((bass - mids + 2f) / 10f) * 0.35f
        val intensity = (80f + (clamp01((deficit - 4f) / 14f) - bodyPenalty) * 20f).coerceIn(80f, 100f)
        val sweep = (34f + clamp01((bass - sub) / 12f) * 22f).coerceIn(27f, 63f)
        val treble = average(4000f, 16000f)
        val width = (40f + clamp01((mids - treble - 6f) / 18f) * 30f).coerceIn(0f, 100f)
        audioEngine.setDspParams(current.copy(intensity = intensity, sweepFreq = sweep, width = width))
        return true
    }

    class Factory(private val audioEngine: AudioEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DspViewModel(audioEngine) as T
        }
    }
}
