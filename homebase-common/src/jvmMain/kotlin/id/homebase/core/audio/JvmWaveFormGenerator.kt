package id.homebase.core.audio

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import io.github.vinceglb.filekit.PlatformFile
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import java.io.IOException
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

class JvmWaveFormGenerator: AudioWaveFormGenerator {
    override fun generateWaveForm(file: PlatformFile): AudioFileInfo {
        val wave = LongArray(AudioWaveFormGenerator.BAR_COUNT)
        val waveSamples = IntArray(AudioWaveFormGenerator.BAR_COUNT)

        val audioFile = File(file.toString())
        val audioInputStream: AudioInputStream = try {
            AudioSystem.getAudioInputStream(audioFile)
        } catch (e: Exception) {
            throw IOException("Failed to open audio file: ${e.message}", e)
        }

        val format = audioInputStream.format
        val sampleRate = format.sampleRate
        val sampleSizeInBits = format.sampleSizeInBits
        val channels = format.channels
        val frameSize = format.frameSize
        val isBigEndian = format.isBigEndian

        // Calculate total frames and duration
        val totalFrames = audioInputStream.frameLength
        if (totalFrames <= 0) {
            audioInputStream.close()
            throw IOException("Unknown audio length")
        }

        val durationUs = ((totalFrames.toDouble() / sampleRate) * 1_000_000).toLong()

        Logger.d(tag = TAG) {
            "Audio format: $sampleRate Hz, $sampleSizeInBits bit, $channels channels, " +
                    "frameSize=$frameSize, totalFrames=$totalFrames, duration=${durationUs}us"
        }

        audioInputStream.use { audioInputStream ->
            // Read audio data in chunks
            val bufferSize = 4096
            val buffer = ByteArray(bufferSize)
            var totalFramesRead = 0L

            while (true) {
                val bytesRead = audioInputStream.read(buffer)
                if (bytesRead <= 0) break

                // Process samples in this buffer
                val framesInBuffer = bytesRead / frameSize

                for (frameIndex in 0 until framesInBuffer) {
                    val currentFrame = totalFramesRead + frameIndex

                    // Determine which bar this frame belongs to
                    val barIndex = ((AudioWaveFormGenerator.BAR_COUNT * currentFrame) / totalFrames).toInt()
                    if (barIndex < 0 || barIndex >= AudioWaveFormGenerator.BAR_COUNT) continue

                    // Read sample value from buffer
                    val frameOffset = frameIndex * frameSize
                    val sampleValue = when (sampleSizeInBits) {
                        8 -> {
                            // 8-bit samples are unsigned (0-255)
                            val unsigned = buffer[frameOffset].toInt() and 0xFF
                            (unsigned - 128).toLong() // Convert to signed
                        }

                        16 -> {
                            // 16-bit samples
                            if (isBigEndian) {
                                ((buffer[frameOffset].toInt() shl 8) or
                                        (buffer[frameOffset + 1].toInt() and 0xFF)).toShort().toLong()
                            } else {
                                ((buffer[frameOffset + 1].toInt() shl 8) or
                                        (buffer[frameOffset].toInt() and 0xFF)).toShort().toLong()
                            }
                        }

                        24 -> {
                            // 24-bit samples (less common)
                            if (isBigEndian) {
                                ((buffer[frameOffset].toInt() shl 16) or
                                        ((buffer[frameOffset + 1].toInt() and 0xFF) shl 8) or
                                        (buffer[frameOffset + 2].toInt() and 0xFF)) shr 8
                            } else {
                                ((buffer[frameOffset + 2].toInt() shl 16) or
                                        ((buffer[frameOffset + 1].toInt() and 0xFF) shl 8) or
                                        (buffer[frameOffset].toInt() and 0xFF)) shr 8
                            }.toLong()
                        }

                        32 -> {
                            // 32-bit samples
                            if (isBigEndian) {
                                ((buffer[frameOffset].toInt() shl 24) or
                                        ((buffer[frameOffset + 1].toInt() and 0xFF) shl 16) or
                                        ((buffer[frameOffset + 2].toInt() and 0xFF) shl 8) or
                                        (buffer[frameOffset + 3].toInt() and 0xFF)).toLong()
                            } else {
                                ((buffer[frameOffset + 3].toInt() shl 24) or
                                        ((buffer[frameOffset + 2].toInt() and 0xFF) shl 16) or
                                        ((buffer[frameOffset + 1].toInt() and 0xFF) shl 8) or
                                        (buffer[frameOffset].toInt() and 0xFF)).toLong()
                            }
                        }

                        else -> {
                            Logger.w(tag = TAG) { "Unsupported sample size: $sampleSizeInBits bits" }
                            0L
                        }
                    }

                    // Accumulate absolute value for this bar
                    wave[barIndex] += abs(sampleValue)
                    waveSamples[barIndex]++
                }

                totalFramesRead += framesInBuffer
            }

            Logger.d(tag = TAG) { "Processed $totalFramesRead frames" }
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

    companion object {
        const val TAG = "JvmWaveFormGenerator"
    }
}