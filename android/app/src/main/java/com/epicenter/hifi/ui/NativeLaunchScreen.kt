package com.epicenter.hifi.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.R
import com.epicenter.hifi.ui.theme.EpicenterTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.math.min

private const val SPLASH_DURATION_MS = 3_600
private const val SPLASH_EXIT_MS = 520
private val IntroRed = Color(0xFFFF102A)
private val PremiumEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val LogoSpringEase = CubicBezierEasing(0.34f, 1.4f, 0.5f, 1f)
private val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Native Compose port of the original "Bass Impact" intro animation. */
@Composable
fun NativeLaunchScreen(onFinished: () -> Unit) {
    EpicenterTheme {
        val scope = rememberCoroutineScope()
        val latestOnFinished by rememberUpdatedState(onFinished)
        var finished by remember { mutableStateOf(false) }
        val sceneAlpha = remember { Animatable(1f) }
        val sceneScale = remember { Animatable(1f) }
        val sceneBlur = remember { Animatable(0f) }

        val finish = remember {
            {
                if (!finished) {
                    finished = true
                    scope.launch {
                        coroutineScope {
                            launch { sceneAlpha.animateTo(0f, tween(SPLASH_EXIT_MS, easing = PremiumEase)) }
                            launch { sceneScale.animateTo(1.08f, tween(SPLASH_EXIT_MS, easing = PremiumEase)) }
                            launch { sceneBlur.animateTo(6f, tween(SPLASH_EXIT_MS, easing = PremiumEase)) }
                        }
                        latestOnFinished()
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            delay((SPLASH_DURATION_MS - SPLASH_EXIT_MS).toLong())
            finish()
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = finish
                )
                .graphicsLayer {
                    alpha = sceneAlpha.value
                    scaleX = sceneScale.value
                    scaleY = sceneScale.value
                    transformOrigin = TransformOrigin(0.5f, 0.46f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sceneBlur.value > 0f) {
                        renderEffect = BlurEffect(sceneBlur.value.dp.toPx(), sceneBlur.value.dp.toPx())
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black)
                val radius = kotlin.math.hypot(size.width * 0.5f, size.height * 0.54f) * 0.46f
                drawRect(
                    brush = Brush.radialGradient(
                        0f to IntroRed.copy(alpha = 0.16f),
                        1f to Color.Transparent,
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.46f),
                        radius = radius
                    )
                )
            }

            val stageSize = minOf(maxWidth * 0.62f, 280.dp)
            Box(Modifier.size(stageSize), contentAlignment = Alignment.Center) {
                IntroRing(stageSize = stageSize, delayMs = 200)
                IntroRing(stageSize = stageSize, delayMs = 430)
                IntroRing(stageSize = stageSize, delayMs = 660)
                IntroBloom(stageSize = stageSize)
                IntroLogo(stageSize = stageSize)
                IntroSweep(stageSize = stageSize)
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxHeight * 0.22f)
                    .height(34.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)
            ) {
                repeat(28) { index -> IntroSpectrumBar(index) }
            }

            IntroFooter(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.09f))
        }
    }
}

@Composable
private fun IntroRing(stageSize: Dp, delayMs: Int) {
    val scale = remember { Animatable(0.12f) }
    val alpha = remember { Animatable(0f) }
    val strokeWidth = remember { Animatable(3f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        coroutineScope {
            launch { scale.animateTo(2.9f, tween(1_900, easing = PremiumEase)) }
            launch {
                alpha.animateTo(
                    0f,
                    keyframes {
                        durationMillis = 1_900
                        0f at 0 using PremiumEase
                        0.95f at 228 using PremiumEase
                        0f at 1_900 using PremiumEase
                    }
                )
            }
            launch { strokeWidth.animateTo(1f, tween(1_900, easing = PremiumEase)) }
        }
    }
    Box(
        Modifier
            .size(stageSize * 0.42f)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }
            .border(strokeWidth.value.dp, IntroRed.copy(alpha = 0.75f), CircleShape)
    )
}

@Composable
private fun IntroBloom(stageSize: Dp) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        delay(300)
        coroutineScope {
            launch { alpha.animateTo(1f, tween(1_100, easing = PremiumEase)) }
            launch { scale.animateTo(1f, tween(1_100, easing = PremiumEase)) }
        }
        alpha.snapTo(0.85f)
        while (isActive) {
            coroutineScope {
                launch { alpha.animateTo(1f, tween(1_500, easing = CssEaseInOut)) }
                launch { scale.animateTo(1.08f, tween(1_500, easing = CssEaseInOut)) }
            }
            coroutineScope {
                launch { alpha.animateTo(0.85f, tween(1_500, easing = CssEaseInOut)) }
                launch { scale.animateTo(1f, tween(1_500, easing = CssEaseInOut)) }
            }
        }
    }
    Box(
        Modifier
            .size(stageSize * 0.78f)
            .graphicsLayer { this.alpha = alpha.value; scaleX = scale.value; scaleY = scale.value }
            .drawWithCache {
                // CSS radial-gradient(circle) uses the farthest corner before
                // the circular background clips it, not the inscribed radius.
                val radius = min(size.width, size.height) * 0.70710678f
                val glow = Brush.radialGradient(
                    0f to IntroRed.copy(alpha = 0.42f),
                    0.68f to Color.Transparent,
                    1f to Color.Transparent,
                    radius = radius
                )
                onDrawBehind { drawCircle(glow) }
            }
            .graphicsLayer {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    renderEffect = BlurEffect(14.dp.toPx(), 14.dp.toPx())
                }
            }
    )
}

@Composable
private fun IntroLogo(stageSize: Dp) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.62f) }
    val blur = remember { Animatable(10f) }
    LaunchedEffect(Unit) {
        delay(400)
        coroutineScope {
            launch {
                alpha.animateTo(
                    1f,
                    keyframes {
                        durationMillis = 1_150
                        0f at 0 using LogoSpringEase
                        1f at 690 using LogoSpringEase
                        1f at 1_150 using LogoSpringEase
                    }
                )
            }
            launch { scale.animateTo(1f, tween(1_150, easing = LogoSpringEase)) }
            launch { blur.animateTo(0f, tween(1_150, easing = LogoSpringEase)) }
        }
    }
    Box(
        modifier = Modifier
            .size(stageSize)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur.value > 0f) {
                        renderEffect = BlurEffect(blur.value.dp.toPx(), blur.value.dp.toPx())
                }
            }
    ) {
        val painter = painterResource(R.drawable.epicenter_logo)
        // Match CSS drop-shadow using the logo's alpha mask, not a box shadow.
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(IntroRed),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val progress = ((scale.value - 0.62f) / 0.38f).coerceIn(0f, 1f)
                this.alpha = 0.35f * progress
                translationY = (12.dp * progress).toPx()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && progress > 0f) {
                    renderEffect = BlurEffect((40.dp * progress).toPx(), (40.dp * progress).toPx())
                }
            }
        )
        Image(painter, "Epicenter Hi-Fi", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun BoxScope.IntroSweep(stageSize: Dp) {
    val alpha = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(1_200)
        coroutineScope {
            launch {
                alpha.animateTo(
                    0f,
                    keyframes {
                        durationMillis = 1_300
                        0f at 0 using PremiumEase
                        1f at 455 using PremiumEase
                        0f at 1_300 using PremiumEase
                    }
                )
            }
            launch {
                width.animateTo(
                    1.02f,
                    keyframes {
                        durationMillis = 1_300
                        0f at 0 using PremiumEase
                        1f at 455 using PremiumEase
                        1.02f at 1_300 using PremiumEase
                    }
                )
            }
        }
    }
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = stageSize * 0.12f)
            .fillMaxWidth(0.88f)
            .height(2.dp)
            .graphicsLayer { scaleX = width.value; this.alpha = alpha.value }
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, IntroRed, Color.Transparent)))
    )
}

@Composable
private fun IntroSpectrumBar(index: Int) {
    val alpha = remember { Animatable(0f) }
    val heightFraction = remember { Animatable(0.08f) }
    val peak = (36 + (index * 37 % 52)) / 100f
    LaunchedEffect(Unit) {
        delay((1_450 + (index % 7) * 70).toLong())
        while (isActive) {
            coroutineScope {
                launch {
                    alpha.animateTo(1f, tween(325, easing = CssEaseInOut))
                    delay(975)
                }
                launch {
                    heightFraction.animateTo(peak, tween(1_300, easing = CssEaseInOut))
                }
            }
            coroutineScope {
                launch {
                    delay(975)
                    alpha.animateTo(0f, tween(325, easing = CssEaseInOut))
                }
                launch {
                    heightFraction.animateTo(0.08f, tween(1_300, easing = CssEaseInOut))
                }
            }
        }
    }
    Box(
        Modifier.width(3.dp).height(34.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(heightFraction.value)
                .graphicsLayer { this.alpha = alpha.value }
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(IntroRed, IntroRed.copy(alpha = 0.35f))))
        )
    }
}

@Composable
private fun IntroFooter(modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    val translateY = remember { Animatable(14f) }
    LaunchedEffect(Unit) {
        delay(1_800)
        coroutineScope {
            launch { alpha.animateTo(1f, tween(900, easing = PremiumEase)) }
            launch { translateY.animateTo(0f, tween(900, easing = PremiumEase)) }
        }
    }
    Column(
        modifier = modifier.graphicsLayer { this.alpha = alpha.value; translationY = translateY.value.dp.toPx() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(IntroRed.copy(alpha = 0.08f))
                .border(1.dp, IntroRed.copy(alpha = 0.4f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 3.dp)
        ) {
            Text("v12.0.0", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "BASS RECONSTRUCTION TECHNOLOGY",
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.8.sp
        )
    }
}
