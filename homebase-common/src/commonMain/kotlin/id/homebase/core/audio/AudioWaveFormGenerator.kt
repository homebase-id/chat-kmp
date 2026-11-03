package id.homebase.core.audio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.vinceglb.filekit.PlatformFile

interface AudioWaveFormGenerator {
    fun generateWaveForm(file: PlatformFile): AudioFileInfo
    fun saveWaveformToPng(amplitudes: FloatArray, width: Int, height: Int): ByteArray

    companion object {
        const val BAR_COUNT = 46
        const val SAMPLES_PER_BAR = 4
        val BAR_COLOR = Color(0xFFF5F5F5)
        const val MIN_BAR_HEIGHT_PERCENT = 0.03f  // 3% minimum height
    }
}

class WaveformBar(val x: Float, val top: Float, val bottom: Float)

class WaveformLayout(val strokeWidth: Float, val bars: List<WaveformBar>)

fun waveformLayout(amplitudes: List<Float>, width: Float, height: Float): WaveformLayout {
    val barWidth = width / amplitudes.size
    val strokeWidth = barWidth * 0.8f
    // Leave room for the round caps, half a stroke at each end.
    val availableHeight = height - strokeWidth
    val bars = amplitudes.mapIndexed { index, amplitude ->
        val adjusted = amplitude.coerceAtLeast(AudioWaveFormGenerator.MIN_BAR_HEIGHT_PERCENT)
        val barHeight = (adjusted * availableHeight).coerceAtMost(availableHeight)
        WaveformBar((index + 0.5f) * barWidth, height / 2 - barHeight / 2, height / 2 + barHeight / 2)
    }
    return WaveformLayout(strokeWidth, bars)
}

fun DrawScope.drawWaveform(amplitudes: List<Float>) {
    val layout = waveformLayout(amplitudes, size.width, size.height)
    layout.bars.forEach { bar ->
        drawLine(
            color = AudioWaveFormGenerator.BAR_COLOR,
            start = Offset(bar.x, bar.top),
            end = Offset(bar.x, bar.bottom),
            strokeWidth = layout.strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
