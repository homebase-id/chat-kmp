package id.homebase.core.audio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.vinceglb.filekit.PlatformFile

interface AudioWaveFormGenerator {
    fun generateWaveForm(file: PlatformFile): AudioFileInfo
    fun saveWaveformToPng(amplitudes: FloatArray, width: Int, height: Int): ByteArray

    companion object {
        const val BAR_COUNT = 46
        const val SAMPLES_PER_BAR = 4
        val BAR_COLOR = Color.Red
    }
}

fun DrawScope.drawWaveform(amplitudes: List<Float>) {
    val barWidth = size.width / amplitudes.size
    amplitudes.forEachIndexed { index, amplitude ->
        val x = index * barWidth
        val barHeight = amplitude * size.height
        drawLine(
            color = AudioWaveFormGenerator.BAR_COLOR,
            start = Offset(x, size.height / 2 - barHeight / 2),
            end = Offset(x, size.height / 2 + barHeight / 2),
            strokeWidth = barWidth * 0.8f
        )
    }
}