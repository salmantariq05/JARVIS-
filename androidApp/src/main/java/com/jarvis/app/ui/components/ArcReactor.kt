package com.jarvis.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.voice.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun phaseColor(phase: Phase): Color = when (phase) {
    Phase.IDLE -> JarvisColors.Cyan
    Phase.LISTENING -> JarvisColors.CyanSoft
    Phase.THINKING -> JarvisColors.Amber
    Phase.SPEAKING -> JarvisColors.Cyan
}

/**
 * The animated arc reactor at the heart of the HUD.
 * Rings spin faster while thinking, the core swells with your voice.
 * Everything is drawn with Canvas: no images needed.
 */
@Composable
fun ArcReactor(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    val color by animateColorAsState(phaseColor(phase), label = "reactorColor")
    val voice by animateFloatAsState(level, label = "voiceLevel")
    val degreesPerSecond = when (phase) {
        Phase.IDLE -> 18f
        Phase.LISTENING -> 45f
        Phase.THINKING -> 160f
        Phase.SPEAKING -> 60f
    }
    val speed by rememberUpdatedState(degreesPerSecond)

    // Frame-by-frame rotation, so speed changes are smooth instead of jumping.
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) angle = (angle + speed * (now - last) / 1_000_000_000f) % 360f
                last = now
            }
        }
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseValue",
    )
    val speakingBoost = if (phase == Phase.SPEAKING) (pulse - 0.88f) * 2.5f else 0f

    Canvas(modifier.aspectRatio(1f)) {
        val c = center
        val r = size.minDimension / 2f

        // Outer glow
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), c, r),
            radius = r,
            center = c,
        )

        // Tick ring (slow, clockwise)
        rotate(angle, c) {
            for (i in 0 until 72) {
                val a = (i * 5f) * PI.toFloat() / 180f
                val long = i % 6 == 0
                val inner = r * (if (long) 0.84f else 0.88f)
                val outer = r * 0.93f
                drawLine(
                    color.copy(alpha = if (long) 0.9f else 0.45f),
                    Offset(c.x + inner * cos(a), c.y + inner * sin(a)),
                    Offset(c.x + outer * cos(a), c.y + outer * sin(a)),
                    strokeWidth = if (long) r * 0.012f else r * 0.006f,
                )
            }
        }

        // Segmented arc ring (counter-clockwise)
        val segRadius = r * 0.74f
        rotate(-angle * 1.6f, c) {
            for (i in 0 until 10) {
                drawArc(
                    color = color.copy(alpha = 0.85f),
                    startAngle = i * 36f + 6f,
                    sweepAngle = 24f,
                    useCenter = false,
                    topLeft = Offset(c.x - segRadius, c.y - segRadius),
                    size = Size(segRadius * 2, segRadius * 2),
                    style = Stroke(width = r * 0.07f, cap = StrokeCap.Butt),
                )
            }
        }

        // Thin inner rings
        drawCircle(color.copy(alpha = 0.7f), radius = r * 0.6f, center = c, style = Stroke(r * 0.012f))
        drawCircle(color.copy(alpha = 0.35f), radius = r * 0.52f, center = c, style = Stroke(r * 0.006f))

        // Triangle (Mark VI reactor), slowly turning
        rotate(angle * 0.5f, c) {
            val tri = Path().apply {
                for (k in 0 until 3) {
                    val a = (-90f + k * 120f) * PI.toFloat() / 180f
                    val p = Offset(c.x + r * 0.46f * cos(a), c.y + r * 0.46f * sin(a))
                    if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(tri, color.copy(alpha = 0.8f), style = Stroke(width = r * 0.025f))
        }

        // Glowing core that swells with your voice and Jarvis's speech
        val coreRadius = r * 0.24f * (pulse + voice * 0.45f + speakingBoost)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White, color.copy(alpha = 0.95f), color.copy(alpha = 0.0f)),
                center = c,
                radius = coreRadius,
            ),
            radius = coreRadius,
            center = c,
        )
    }
}

/** Equaliser bars that dance with your voice (listening) or Jarvis's (speaking). */
@Composable
fun VoiceWave(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    val color by animateColorAsState(phaseColor(phase), label = "waveColor")
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { now -> t = now / 1_000_000_000f }
    }
    val activity = when (phase) {
        Phase.LISTENING -> 0.15f + level * 0.85f
        Phase.SPEAKING -> 0.55f
        Phase.THINKING -> 0.2f
        Phase.IDLE -> 0.06f
    }
    Canvas(modifier) {
        val bars = 32
        val gap = size.width / bars
        for (i in 0 until bars) {
            val wobble = (sin(t * 7f + i * 0.55f) + sin(t * 3.3f + i * 1.3f)) / 4f + 0.5f
            val h = (size.height * activity * wobble).coerceAtLeast(size.height * 0.04f)
            val x = i * gap + gap / 2
            drawLine(
                color.copy(alpha = 0.85f),
                Offset(x, size.height / 2 - h / 2),
                Offset(x, size.height / 2 + h / 2),
                strokeWidth = gap * 0.5f,
                cap = StrokeCap.Round,
            )
        }
    }
}
