package com.epicenter.hifi.data.model

import android.net.Uri

data class AudioTrack(
    val id: String,
    val stableId: String = id,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val duration: Double = 0.0,
    val uri: String,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val channels: Int? = null,
    val isHiRes: Boolean = false,
    val albumArtUri: String? = null,
    val size: Long = 0L,
    val dateAdded: Long = 0L
) {
    val durationMs: Long
        get() = (duration * 1000).toLong()

    val formattedDuration: String
        get() {
            val totalSeconds = duration.toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }

    val audioQualityLabel: String
        get() = when {
            isHiRes && sampleRate != null && sampleRate >= 96000 -> "Hi-Res Lossless"
            isHiRes -> "Hi-Res"
            bitDepth != null && bitDepth >= 24 -> "24-bit"
            sampleRate != null && sampleRate >= 48000 -> "Lossless"
            else -> "Standard"
        }
}

data class DspParams(
    val sweepFreq: Float = 45f,      // 27Hz - 63Hz
    val width: Float = 50f,          // 0% - 100%
    val intensity: Float = 100f,     // 0% - 100%
    val balance: Float = 100f,       // 0% - 100%
    val volume: Float = 100f,        // 0% - 100%
    val enabled: Boolean = true,
    val mode: String = "car"         // "car" | "headphones"
)

data class EqParams(
    val enabled: Boolean = true,
    val bands: FloatArray = FloatArray(31) { 0f }, // 31 bands from 20Hz to 20kHz, in dB (-12dB to +12dB)
    val preampDb: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EqParams
        return enabled == other.enabled && bands.contentEquals(other.bands) && preampDb == other.preampDb
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + bands.contentHashCode()
        result = 31 * result + preampDb.hashCode()
        return result
    }
}
