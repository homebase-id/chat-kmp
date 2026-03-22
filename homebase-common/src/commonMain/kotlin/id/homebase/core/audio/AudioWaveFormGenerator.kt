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
        const val MIN_BAR_HEIGHT_PERCENT = 0.05f  // 5% minimum height
    }
}

fun DrawScope.drawWaveform(amplitudes: List<Float>) {
    val barWidth = size.width / amplitudes.size
    val strokeWidth = barWidth * 0.8f

    // Reserve space for rounded caps (half stroke width on each end)
    val verticalPadding = strokeWidth / 2f
    val availableHeight = size.height - (verticalPadding * 2)

    amplitudes.forEachIndexed { index, amplitude ->
        val x = index * barWidth

        // Apply minimum height constraint
        val adjustedAmplitude = amplitude.coerceAtLeast(AudioWaveFormGenerator.MIN_BAR_HEIGHT_PERCENT)

        // Calculate bar height within the available space (leaving room for caps)
        val barHeight = (adjustedAmplitude * availableHeight).coerceAtMost(availableHeight)

        drawLine(
            color = AudioWaveFormGenerator.BAR_COLOR,
            start = Offset(x, size.height / 2 - barHeight / 2),
            end = Offset(x, size.height / 2 + barHeight / 2),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}