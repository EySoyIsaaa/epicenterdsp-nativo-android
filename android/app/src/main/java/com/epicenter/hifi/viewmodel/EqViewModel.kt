package com.epicenter.hifi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.epicenter.hifi.data.model.EqParams
import com.epicenter.hifi.engine.AudioEngine
import kotlinx.coroutines.flow.StateFlow

data class EqPreset(
    val name: String,
    val gains: FloatArray,
    val preampDb: Float = 0f
)

class EqViewModel(private val audioEngine: AudioEngine) : ViewModel() {

    val eqParams: StateFlow<EqParams> = audioEngine.eqParams
    val spectrumBands: StateFlow<FloatArray> = audioEngine.spectrumBands

    val frequencyLabels: List<String> = listOf(
        "20", "25", "31", "40", "50", "63", "80", "100", "125", "160",
        "200", "250", "315", "400", "500", "630", "800", "1k", "1.25k", "1.6k",
        "2k", "2.5k", "3.15k", "4k", "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k"
    )

    val presets: List<EqPreset> = listOf(
        EqPreset("Flat", FloatArray(31) { 0f }),
        EqPreset("Bass Boost", FloatArray(31) { i -> if (i < 8) (8 - i) * 1.0f else 0f }, preampDb = -2f),
        EqPreset("Vocal Boost", FloatArray(31) { i -> if (i in 12..20) 3.5f else 0f }),
        EqPreset("Treble Boost", FloatArray(31) { i -> if (i >= 20) (i - 19) * 0.7f else 0f }, preampDb = -1f),
        EqPreset("Rock", FloatArray(31) { i ->
            when {
                i < 6 -> 4.0f
                i in 6..12 -> 2.0f
                i in 13..18 -> -1.5f
                i in 19..25 -> 2.5f
                else -> 4.5f
            }
        }, preampDb = -2f)
    )

    fun setEqEnabled(enabled: Boolean) {
        audioEngine.setEqEnabled(enabled)
    }

    fun setBandGain(index: Int, gainDb: Float) {
        audioEngine.setBandGain(index, gainDb)
    }

    fun setPreamp(preampDb: Float) {
        audioEngine.setPreamp(preampDb)
    }

    fun resetEq() {
        audioEngine.resetEq()
    }

    fun applyPreset(preset: EqPreset) {
        audioEngine.setPreamp(preset.preampDb)
        preset.gains.forEachIndexed { i, gain ->
            audioEngine.setBandGain(i, gain)
        }
    }

    class Factory(private val audioEngine: AudioEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EqViewModel(audioEngine) as T
        }
    }
}
