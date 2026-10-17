package com.rafeeq.assistant.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun VoiceWaveform(
    modifier: Modifier = Modifier,
    barCount: Int = 5,
    barWidth: Dp = 4.dp,
    barMinHeight: Dp = 4.dp,
    barMaxHeight: Dp = 24.dp,
    barSpacing: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    isAnimating: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val density = LocalDensity.current
    Canvas(modifier = modifier) {
        val barWidthPx = with(density) { barWidth.toPx() }
        val barSpacingPx = with(density) { barSpacing.toPx() }
        val barMinHeightPx = with(density) { barMinHeight.toPx() }
        val barMaxHeightPx = with(density) { barMaxHeight.toPx() }
        val totalWidth = barWidthPx * barCount + barSpacingPx * (barCount - 1)
        val startX = (size.width - totalWidth) / 2
        val centerY = size.height / 2

        for (i in 0 until barCount) {
            val amplitude = if (isAnimating) {
                val t = phase + (i * Math.PI.toFloat() / barCount)
                (sin(t.toDouble()) * 0.5 + 0.5).toFloat()
            } else {
                0.3f
            }
            val barHeight = barMinHeightPx + (barMaxHeightPx - barMinHeightPx) * amplitude
            val x = startX + i * (barWidthPx + barSpacingPx)
            val top = centerY - barHeight / 2
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(barWidthPx, barHeight),
                cornerRadius = CornerRadius(barWidthPx / 2)
            )
        }
    }
}