package com.epicenter.hifi.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.epicenter.hifi.R
import com.epicenter.hifi.data.model.AudioQualityTier
import com.epicenter.hifi.data.model.AudioTrack

private val HiResGold = Color(0xFFD8B45A)
private val CdSilver = Color(0xFFC7CBD2)
private val StandardGray = Color(0xFF929299)

private object HiResLogoLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
            .also { instance = it }
    }
}

@Composable
fun HiResAudioLogo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageLoader = remember(context) { HiResLogoLoader.get(context) }
    AsyncImage(
        model = R.raw.hires_audio,
        imageLoader = imageLoader,
        contentDescription = "Hi-Res Audio",
        modifier = modifier
    )
}

@Composable
fun AudioQualityBadge(track: AudioTrack, compact: Boolean = false) {
    val (label, color, fill) = when (track.qualityTier) {
        AudioQualityTier.HI_RES -> Triple("HI-RES AUDIO", HiResGold, Color(0x332E2514))
        AudioQualityTier.CD -> Triple("CD QUALITY", CdSilver, Color(0x33272A30))
        AudioQualityTier.STANDARD -> Triple("STANDARD", StandardGray, Color(0x33242428))
    }
    Row(
        modifier = Modifier
            .background(fill, RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = if (track.qualityTier == AudioQualityTier.STANDARD) .36f else .72f), RoundedCornerShape(8.dp))
            .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (track.qualityTier == AudioQualityTier.HI_RES) {
            HiResAudioLogo(Modifier.size(if (compact) 27.dp else 36.dp))
        } else if (track.qualityTier != AudioQualityTier.STANDARD) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = color, modifier = Modifier.size(if (compact) 12.dp else 16.dp))
        }
        if (track.qualityTier != AudioQualityTier.HI_RES) {
            Text(
                text = label,
                color = color,
                fontSize = if (compact) 7.sp else 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = if (compact) .35.sp else .8.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
fun TrackFormatDetails(track: AudioTrack) {
    val details = buildList {
        track.bitDepth?.takeIf { it > 0 }?.let { add("${it} bit") }
        track.sampleRate?.takeIf { it > 0 }?.let { add(String.format(java.util.Locale.US, "%.1f kHz", it / 1_000f)) }
        track.bitrateKbps?.let { add("$it kbps") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        AudioQualityBadge(track)
        details.forEach { detail ->
            Text(detail, color = Color(0xFFB8B8BE), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
