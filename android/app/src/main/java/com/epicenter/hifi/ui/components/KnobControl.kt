package com.epicenter.hifi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.DspActiveArc
import com.epicenter.hifi.ui.theme.TextPrimary
import com.epicenter.hifi.ui.theme.TextSecondary
import com.epicenter.hifi.ui.theme.TrackBackground
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun KnobControl(
    label: String,
    value: Float,
    minValue: Float,
    maxValue: Float,
    unit: String = "",
    size: Dp = 130.dp,
    enabled: Boolean = true,
    showValue: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit
) {
    val totalAngle = 270f
    val startAngle = 135f
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val knobSizePx = with(density) { size.toPx() }
    val latestValue = rememberUpdatedState(value)
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished = rememberUpdatedState(onValueChangeFinished)
    val normalizedFraction = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .pointerInput(enabled, minValue, maxValue, knobSizePx) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragEnd = { latestOnValueChangeFinished.value?.invoke() },
                        onDragCancel = { latestOnValueChangeFinished.value?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val valuePerPixel = (maxValue - minValue) / knobSizePx
                            val nextValue = (latestValue.value - dragAmount.y * valuePerPixel)
                                .coerceIn(minValue, maxValue)
                            latestOnValueChange.value(nextValue)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(size)) {
                val strokeWidth = 10.dp.toPx()
                val radius = (size.toPx() - strokeWidth) / 2f
                val centerOffset = Offset(size.toPx() / 2f, size.toPx() / 2f)

                // Polished dark-chrome bezel with a moving metallic reflection.
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color(0xFF5C5D61), Color(0xFFE3E4E6), Color(0xFF66676B),
                            Color(0xFF252629), Color(0xFFD1D2D4), Color(0xFF5C5D61)
                        ),
                        center = centerOffset
                    ),
                    radius = radius * 0.91f,
                    center = centerOffset,
                    style = Stroke(width = 3.dp.toPx())
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = if (darkTheme) listOf(Color(0xFF4A4B4F), Color(0xFF222326), Color(0xFF101113), Color(0xFF09090A))
                        else listOf(Color.White, Color(0xFFE9EAED), Color(0xFFBFC1C6), Color(0xFFF9F9FA)),
                        center = Offset(centerOffset.x * 0.7f, centerOffset.y * 0.62f),
                        radius = radius * 1.45f
                    ),
                    radius = radius * 0.82f,
                    center = centerOffset
                )
                drawCircle(
                    color = (if (darkTheme) Color.White else Color.Black).copy(alpha = 0.07f),
                    radius = radius * 0.82f,
                    center = centerOffset,
                    style = Stroke(width = 1.dp.toPx())
                )

                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.01f)),
                        start = Offset(centerOffset.x * 0.42f, centerOffset.y * 0.4f),
                        end = Offset(centerOffset.x * 1.45f, centerOffset.y * 1.15f)
                    ),
                    start = Offset(centerOffset.x * 0.48f, centerOffset.y * 0.43f),
                    end = Offset(centerOffset.x * 1.42f, centerOffset.y * 1.17f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // 1. Fondo del arco (gris oscuro)
                drawArc(
                    color = if (darkTheme) TrackBackground else Color(0xFFD6D6DC),
                    startAngle = startAngle,
                    sweepAngle = totalAngle,
                    useCenter = false,
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // 2. Arco activo con gradiente
                val sweepAngle = totalAngle * normalizedFraction
                if (sweepAngle > 0f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                        colors = listOf(AccentRed, DspActiveArc, Color(0xFFFF5E5E)),
                            center = centerOffset
                        ),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                // 3. Indicador de punto / aguja
                val currentAngleRad = ((startAngle + sweepAngle) * PI / 180f).toFloat()
                val indicatorX = centerOffset.x + radius * cos(currentAngleRad)
                val indicatorY = centerOffset.y + radius * sin(currentAngleRad)

                drawLine(
                    color = (if (darkTheme) Color.White else Color(0xFF57575C)).copy(alpha = 0.72f),
                    start = Offset(
                        centerOffset.x + (indicatorX - centerOffset.x) * 0.42f,
                        centerOffset.y + (indicatorY - centerOffset.y) * 0.42f
                    ),
                    end = Offset(
                        centerOffset.x + (indicatorX - centerOffset.x) * 0.74f,
                        centerOffset.y + (indicatorY - centerOffset.y) * 0.74f
                    ),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFFF0F1F2), Color(0xFF85868A), Color(0xFF292A2D)),
                        center = centerOffset,
                        radius = 5.dp.toPx()
                    ),
                    radius = 3.dp.toPx(),
                    center = centerOffset
                )
            }

            // Texto central de valor
            if (showValue) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val displayValue = if (unit == "Hz") {
                    value.toInt().toString()
                } else {
                    value.toInt().toString()
                }

                Text(
                    text = "$displayValue$unit",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Etiqueta del Knob
        Text(
            text = label.uppercase(),
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}
