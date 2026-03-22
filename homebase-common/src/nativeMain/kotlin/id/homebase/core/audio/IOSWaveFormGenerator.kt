package id.homebase.core.audio

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.io.IOException
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSError
import platform.Foundation.NSURL
import kotlin.math.abs


class IOSWaveFormGenerator : AudioWaveFormGenerator {
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun generateWaveForm(file: PlatformFile): AudioFileInfo {
        val url = NSURL.fileURLWithPath(file.toString())

        // Load the audio file
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val audioFile = AVAudioFile(url, errorPtr.ptr)

            if (errorPtr.value != null) {
                throw IOException("Failed to open audio file: ${errorPtr.value?.localizedDescription}")
            }

            val format = audioFile.processingFormat
            val frameCount = audioFile.length.toInt()
            val sampleRate = format.sampleRate

            // Calculate duration in microseconds
            val durationUs = ((frameCount.toDouble() / sampleRate) * 1_000_000).toLong()

            // Calculate samples per bar
            val framesPerBar = frameCount / AudioWaveFormGenerator.BAR_COUNT

            // Prepare buffer for reading
            val bufferCapacity = 4096u
            val buffer = AVAudioPCMBuffer(format, bufferCapacity)

            val wave = LongArray(AudioWaveFormGenerator.BAR_COUNT)
            val waveSamples = IntArray(AudioWaveFormGenerator.BAR_COUNT)

            var currentFrame = 0

            // Read and process audio data
            while (currentFrame < frameCount) {
                audioFile.readIntoBuffer(buffer, errorPtr.ptr)

                if (errorPtr.value != null) {
                    break
                }

                val frameLength = buffer.frameLength.toInt()
                if (frameLength == 0) break

                // Get pointer to the audio data
                val floatChannelData = buffer.floatChannelData
                if (floatChannelData != null) {
                    val channelDataPtr = floatChannelData[0]

                    // Process each sample
                    for (i in 0 until frameLength) {
                        val barIndex = (currentFrame + i) / framesPerBar
                        if (barIndex >= AudioWaveFormGenerator.BAR_COUNT) break

                        // Read sample value (using first channel)
                        val sample = channelDataPtr!![i]
                        val absoluteValue = abs(sample)

                        // Accumulate for this bar
                        wave[barIndex] += (absoluteValue * 32767).toLong() // Convert to 16-bit range
                        waveSamples[barIndex]++
                    }
                }

                currentFrame += frameLength
            }

            // Normalize the waveform
            val floats = FloatArray(AudioWaveFormGenerator.BAR_COUNT)
            val bytes = ByteArray(AudioWaveFormGenerator.BAR_COUNT)
            var max = 0f

            for (i in 0 until AudioWaveFormGenerator.BAR_COUNT) {
                if (waveSamples[i] == 0) continue

                floats[i] = wave[i] / waveSamples[i].toFloat()
                if (floats[i] > max) {
                    max = floats[i]
                }
            }

            for (i in 0 until AudioWaveFormGenerator.BAR_COUNT) {
                val normalized = if (max > 0) floats[i] / max else 0f
                bytes[i] = (255 * normalized).toInt().toByte()
            }

            return AudioFileInfo(durationUs, bytes)
        }
    }

    override fun saveWaveformToPng(amplitudes: FloatArray, width: Int, height: Int): ByteArray {
        // 1. Create a Skia Surface
        val surface = Surface.makeRasterN32Premul(width, height)
        val skiaCanvas = surface.canvas

        // 2. Wrap the Skia Canvas into a Compose Canvas
        val composeCanvas = skiaCanvas.asComposeCanvas()

        // 3. Use CanvasDrawScope to bridge the two
        val drawScope = CanvasDrawScope()
        drawScope.draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = Size(width.toFloat(), height.toFloat())
        ) {
            drawWaveform(amplitudes.toList())
        }

        // 4. Encode to PNG
        val image = surface.makeImageSnapshot()
        val data = image.encodeToData(EncodedImageFormat.PNG, 100)
        return data?.bytes ?: byteArrayOf()
    }
}