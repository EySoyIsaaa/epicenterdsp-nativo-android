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
        audioEngine.updateSweep(sweep)
    }

    fun setWidth(width: Float) {
        audioEngine.updateWidth(width)
    }

    fun setIntensity(intensity: Float) {
        audioEngine.updateIntensity(intensity)
    }

    fun setBalance(balance: Float) {
        audioEngine.updateBalance(balance)
    }

    fun setVolume(volume: Float) {
        audioEngine.updateVolume(volume)
    }

    fun setMode(mode: String) {
        audioEngine.setEpicenterMode(mode)
    }

    class Factory(private val audioEngine: AudioEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DspViewModel(audioEngine) as T
        }
    }
}
