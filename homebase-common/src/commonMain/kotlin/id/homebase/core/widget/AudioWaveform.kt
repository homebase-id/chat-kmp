package id.homebase.core.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.resources.MR
import id.homebase.resources.audio_waveform_seek
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

private object WaveformGeometry {
    val barWidth = 2.5.dp
    val barMinGap = 1.dp
    val height = 40.dp
    val thumbWidth = 2.dp
    val thumbCorner = 1.dp

    // The near-black surfaceContainerHigh swallows the light-theme alpha.
    const val UNPLAYED_ALPHA_LIGHT = 0.30f
    const val UNPLAYED_ALPHA_DARK = 0.45f

    const val GROW_MS = 450f
    const val BAR_STAGGER_MS = 12f
    const val OVERSHOOT_TENSION = 2.0f

    val totalGrowMs =
        (GROW_MS + (AudioWaveFormGenerator.BAR_COUNT - 1) * BAR_STAGGER_MS).toInt()
}

@Composable
fun AudioWaveform(
    amplitudes: FloatArray?,
    progress: () -> Float,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    unplayedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (isSystemInDarkTheme()) {
            WaveformGeometry.UNPLAYED_ALPHA_DARK
        } else {
            WaveformGeometry.UNPLAYED_ALPHA_LIGHT
        }
    ),
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val description = stringResource(MR.string.audio_waveform_seek)
    val grow = remember { Animatable(0f) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(amplitudes) {
        grow.snapTo(0f)
        if (amplitudes != null) {
            grow.animateTo(
                targetValue = 1f,
                animationSpec = tween(WaveformGeometry.totalGrowMs, easing = LinearEasing),
            )
        }
    }

    val seekModifier = if (onSeek == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(onSeek, isRtl) {
                detectTapGestures { offset ->
                    onSeek(fractionOf(offset.x, size.width.toFloat(), isRtl))
                }
            }
            .pointerInput(onSeek, isRtl) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragProgress = fractionOf(offset.x, size.width.toFloat(), isRtl)
                    },
                    onDragEnd = {
                        dragProgress?.let(onSeek)
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                ) { change, _ ->
                    dragProgress = fractionOf(change.position.x, size.width.toFloat(), isRtl)
                }
            }
    }

    Canvas(
        modifier = modifier
            .height(WaveformGeometry.height)
            .semantics { contentDescription = description }
            .then(seekModifier)
    ) {
        val dragging = dragProgress != null
        val shown = (dragProgress ?: progress()).coerceIn(0f, 1f)
        if (isRtl) {
            scale(scaleX = -1f, scaleY = 1f) {
                drawWaveformBars(amplitudes, shown, dragging, grow.value, playedColor, unplayedColor)
            }
        } else {
            drawWaveformBars(amplitudes, shown, dragging, grow.value, playedColor, unplayedColor)
        }
    }
}

private fun DrawScope.drawWaveformBars(
    amplitudes: FloatArray?,
    progress: Float,
    dragging: Boolean,
    grow: Float,
    playedColor: Color,
    unplayedColor: Color,
) {
    val width = size.width
    if (width <= 0f || size.height <= 0f) return

    val barWidth = WaveformGeometry.barWidth.toPx()
    val minGap = WaveformGeometry.barMinGap.toPx()

    var barCount = amplitudes?.size?.takeIf { it > 0 } ?: AudioWaveFormGenerator.BAR_COUNT
    if (width < barCount * barWidth + (barCount - 1) * minGap) {
        barCount = floor((width + minGap) / (barWidth + minGap)).toInt()
    }
    if (barCount < 1) return

    val values = when {
        amplitudes == null -> null
        barCount < amplitudes.size -> resampleAverage(amplitudes, barCount)
        else -> amplitudes
    }

    val gap = if (barCount > 1) (width - barCount * barWidth) / (barCount - 1) else 0f
    val mid = size.height / 2f
    val maxHalfHeight = (size.height - barWidth) / 2f
    val minHalfHeight = barWidth / 2f
    val elapsedMs = grow * WaveformGeometry.totalGrowMs

    for (index in 0 until barCount) {
        val x = index * (barWidth + gap) + barWidth / 2f
        val amplitude = values?.getOrNull(index)?.coerceIn(0f, 1f) ?: 0f
        val t = ((elapsedMs - index * WaveformGeometry.BAR_STAGGER_MS) / WaveformGeometry.GROW_MS)
            .coerceIn(0f, 1f)
        val halfHeight =
            maxOf(amplitude * maxHalfHeight, minHalfHeight) * overshoot(t)
        drawLine(
            color = if (x / width < progress) playedColor else unplayedColor,
            start = Offset(x, mid - halfHeight),
            end = Offset(x, mid + halfHeight),
            strokeWidth = barWidth,
            cap = StrokeCap.Round,
        )
    }

    if (progress > 0f || dragging) {
        val thumbWidth = WaveformGeometry.thumbWidth.toPx()
        val thumbX = (progress * width).coerceIn(thumbWidth / 2f, width - thumbWidth / 2f)
        drawRoundRect(
            color = playedColor,
            topLeft = Offset(thumbX - thumbWidth / 2f, 0f),
            size = Size(thumbWidth, size.height),
            cornerRadius = CornerRadius(WaveformGeometry.thumbCorner.toPx()),
        )
    }
}

private fun overshoot(t: Float): Float {
    val p = t - 1f
    return p * p * ((WaveformGeometry.OVERSHOOT_TENSION + 1f) * p + WaveformGeometry.OVERSHOOT_TENSION) + 1f
}

private fun resampleAverage(source: FloatArray, barCount: Int): FloatArray {
    val out = FloatArray(barCount)
    for (index in 0 until barCount) {
        val from = (index * source.size) / barCount
        val to = ((index + 1) * source.size / barCount)
            .coerceAtLeast(from + 1)
            .coerceAtMost(source.size)
        var sum = 0f
        for (j in from until to) sum += source[j]
        out[index] = sum / (to - from)
    }
    return out
}

private fun fractionOf(x: Float, width: Float, isRtl: Boolean): Float {
    if (width <= 0f) return 0f
    val logical = if (isRtl) width - x else x
    return (logical / width).coerceIn(0f, 1f)
}
