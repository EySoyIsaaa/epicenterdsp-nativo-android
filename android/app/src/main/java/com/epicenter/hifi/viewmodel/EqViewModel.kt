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

    fun setBandGainRealtime(index: Int, gainDb: Float) {
        audioEngine.setBandGainRealtime(index, gainDb)
    }

    fun persistBandChanges() {
        audioEngine.persistCurrentEqParams()
    }

    fun setPreamp(preampDb: Float) {
        audioEngine.setPreamp(preampDb)
    }

    fun resetEq() {
        audioEngine.resetEq()
    }

    fun applyPreset(preset: EqPreset) {
        audioEngine.setPreamp(preset.preampDb)
        audioEngine.setEqBands(preset.gains)
    }

    fun autoTune(): Boolean {
        val measured = spectrumBands.value
        if (measured.size != 12 || measured.all { it <= -100f }) return false
        val edges = floatArrayOf(20f, 40f, 60f, 90f, 125f, 180f, 250f, 400f, 800f, 2000f, 4000f, 8000f, 16000f)
        val centers = FloatArray(measured.size) { index -> kotlin.math.sqrt(edges[index] * edges[index + 1]) }
        val alive = measured.filter { it > -100f }
        if (alive.isEmpty()) return false
        val mean = alive.average().toFloat()
        val deviation = FloatArray(measured.size) { index ->
            if (measured[index] <= -100f) 0f else measured[index] - (mean - 3f * kotlin.math.log2((centers[index] / 200f).coerceAtLeast(0.1f)).coerceIn(-14f, 6f))
        }
        val smooth = FloatArray(deviation.size) { index ->
            val previous = deviation.getOrElse(index - 1) { deviation[index] }
            val next = deviation.getOrElse(index + 1) { deviation[index] }
            (previous + 2f * deviation[index] + next) / 4f
        }
        val gains = FloatArray(frequencyLabels.size) { band ->
            val hz = FREQUENCIES[band]
            var low = 0
            while (low + 1 < centers.size && centers[low + 1] < hz) low++
            val high = (low + 1).coerceAtMost(centers.lastIndex)
            val span = kotlin.math.log2(centers[high] / centers[low]).takeIf { it.isFinite() && it > 1e-6f } ?: 1f
            val t = (kotlin.math.log2(hz / centers[low]) / span).coerceIn(0f, 1f)
            -(smooth[low] * (1f - t) + smooth[high] * t) * 0.45f
        }
        val average = gains.average().toFloat()
        val centered = FloatArray(gains.size) { gains[it] - average }
        val maxPositive = centered.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        val maxNegative = centered.minOrNull()?.let { -it }?.coerceAtLeast(0f) ?: 0f
        val scale = minOf(
            1f,
            if (maxPositive > 1.5f) 1.5f / maxPositive else 1f,
            if (maxNegative > 3f) 3f / maxNegative else 1f
        )
        val tuned = FloatArray(centered.size) { index -> (centered[index] * scale).coerceIn(-3f, 1.5f) }
        audioEngine.setEqBands(tuned)
        audioEngine.setPreamp(-(tuned.maxOrNull()?.coerceAtLeast(0f) ?: 0f).coerceAtMost(3f))
        return true
    }

    class Factory(private val audioEngine: AudioEngine) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EqViewModel(audioEngine) as T
        }
    }

    companion object {
        private val FREQUENCIES = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
            200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
            2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
        )
    }
}
