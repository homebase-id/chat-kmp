package id.homebase.core.audio

import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Surface

// Straight Skia: a Compose DrawScope needs a registered UI graphics impl, which exists only once a Compose host is up.
internal fun skiaWaveformPng(amplitudes: FloatArray, width: Int, height: Int): ByteArray {
    val surface = Surface.makeRasterN32Premul(width, height)
    val layout = waveformLayout(amplitudes.toList(), width.toFloat(), height.toFloat())
    val paint = Paint().apply {
        color = AudioWaveFormGenerator.BAR_COLOR.toArgb()
        mode = PaintMode.STROKE
        strokeWidth = layout.strokeWidth
        strokeCap = PaintStrokeCap.ROUND
        isAntiAlias = true
    }
    try {
        layout.bars.forEach { surface.canvas.drawLine(it.x, it.top, it.x, it.bottom, paint) }
        val image = surface.makeImageSnapshot()
        try {
            return image.encodeToData(EncodedImageFormat.PNG, 100)?.bytes ?: byteArrayOf()
        } finally {
            image.close()
        }
    } finally {
        paint.close()
        surface.close()
    }
}
