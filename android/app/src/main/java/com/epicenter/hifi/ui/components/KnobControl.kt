package com.epicenter.hifi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.ui.theme.AccentGold
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
    onValueChange: (Float) -> Unit
) {
    val totalAngle = 270f
    val startAngle = 135f

    val normalizedFraction = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .pointerInput(minValue, maxValue, value) {
                    detectDragGestures(
                        onDragStart = { dragAccumulator = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // Arrastre vertical: hacia arriba aumenta, hacia abajo disminuye
                            // Sensibilidad de arrastre suave
                            dragAccumulator -= dragAmount.y
                            val sensitivity = (maxValue - minValue) / 300f
                            val delta = dragAmount.y * -sensitivity
                            val newValue = (value + delta).coerceIn(minValue, maxValue)
                            onValueChange(newValue)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(size)) {
                val strokeWidth = 10.dp.toPx()
                val radius = (size.toPx() - strokeWidth) / 2f
                val centerOffset = Offset(size.toPx() / 2f, size.toPx() / 2f)

                // 1. Fondo del arco (gris oscuro)
                drawArc(
                    color = TrackBackground,
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
                            colors = listOf(AccentGold, DspActiveArc, Color(0xFFFF5E00)),
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

                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(indicatorX, indicatorY)
                )
            }

            // Texto central de valor
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
