package id.homebase.core.audio

import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformRasterTest {

    // Measured worst-case round-trip error is 0.021 at 320x64 and 0.007 at 1000x200.
    private val tolerance = 0.03f

    private fun sampleAmplitudes(): FloatArray = FloatArray(AudioWaveFormGenerator.BAR_COUNT) { i ->
        when (i) {
            0 -> 0f
            7 -> 1f
            20 -> 0f
            45 -> 1f
            else -> (0.5f + 0.5f * sin(i * 0.4f)) * (i / 45f)
        }
    }

    private fun assertRoundTrip(width: Int, height: Int) {
        val expected = sampleAmplitudes()
        val png = JvmWaveFormGenerator().saveWaveformToPng(expected, width, height)
        assertTrue(png.isNotEmpty(), "rasteriser produced no bytes at ${width}x$height")

        val decoded = png.decodeToImageBitmap().toWaveformAmplitudes()
        assertEquals(AudioWaveFormGenerator.BAR_COUNT, decoded.size)

        for (i in expected.indices) {
            // The rasteriser floors every bar, so that is what a faithful decode returns.
            val floored = expected[i].coerceAtLeast(AudioWaveFormGenerator.MIN_BAR_HEIGHT_PERCENT)
            val error = abs(decoded[i] - floored)
            assertTrue(
                error <= tolerance,
                "bar $i at ${width}x$height: expected $floored, decoded ${decoded[i]}, error $error"
            )
        }
    }

    @Test
    fun `round trips 46 bars through a 320x64 raster`() = assertRoundTrip(320, 64)

    @Test
    fun `round trips 46 bars through a 1000x200 raster`() = assertRoundTrip(1000, 200)

    @Test
    fun `returns an empty array for a degenerate bitmap`() {
        val png = JvmWaveFormGenerator().saveWaveformToPng(FloatArray(2) { 1f }, 2, 2)
        assertEquals(0, png.decodeToImageBitmap().toWaveformAmplitudes().size)
    }
}
