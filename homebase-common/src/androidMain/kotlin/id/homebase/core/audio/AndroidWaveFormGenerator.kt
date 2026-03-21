package id.homebase.core.audio

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.WorkerThread
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.createBitmap
import co.touchlab.kermit.Logger
import io.github.vinceglb.filekit.PlatformFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs

class AndroidWaveFormGenerator: AudioWaveFormGenerator {
    /**
     * Based on decode sample from:
     *
     *
     * https://android.googlesource.com/platform/cts/+/jb-mr2-release/tests/tests/media/src/android/media/cts/DecoderTest.java
     */
    @WorkerThread
    override fun generateWaveForm(file: PlatformFile): AudioFileInfo {

        val wave = LongArray(AudioWaveFormGenerator.BAR_COUNT)
        val waveSamples = IntArray(AudioWaveFormGenerator.BAR_COUNT)

        val extractor: MediaExtractor = createExtractor(file)

        if (extractor.trackCount == 0) {
            throw IOException("No audio track")
        }

        val format = extractor.getTrackFormat(0)

        if (!format.containsKey(MediaFormat.KEY_DURATION)) {
            throw IOException("Unknown duration")
        }

        val totalDurationUs = format.getLong(MediaFormat.KEY_DURATION)
        val mime = format.getString(MediaFormat.KEY_MIME)

        if (mime == null || !mime.startsWith("audio/")) {
            throw IOException("Mime not audio")
        }

        val codec = MediaCodec.createDecoderByType(mime)

        if (totalDurationUs == 0L) {
            throw IOException("Zero duration")
        }

        codec.configure(format, null, null, 0)
        codec.start()

        extractor.selectTrack(0)

        val kTimeOutUs: Long = 5000
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var noOutputCounter = 0

        while (!sawOutputEOS && noOutputCounter < 50) {
            noOutputCounter++
            if (!sawInputEOS) {
                val inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs)
                if (inputBufIndex >= 0) {
                    val dstBuf = codec.getInputBuffer(inputBufIndex) ?: throw IOException("No input buffer")
                    var sampleSize = extractor.readSampleData(dstBuf, 0)
                    var presentationTimeUs: Long = 0

                    if (sampleSize < 0) {
                        sawInputEOS = true
                        sampleSize = 0
                    } else {
                        presentationTimeUs = extractor.sampleTime
                    }

                    codec.queueInputBuffer(
                        inputBufIndex,
                        0,
                        sampleSize,
                        presentationTimeUs,
                        if (sawInputEOS) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    )

                    if (!sawInputEOS) {
                        val barSampleIndex =
                            (AudioWaveFormGenerator.SAMPLES_PER_BAR * (wave.size * extractor.sampleTime) / totalDurationUs).toInt()
                        sawInputEOS = !extractor.advance()
                        var nextBarSampleIndex =
                            (AudioWaveFormGenerator.SAMPLES_PER_BAR * (wave.size * extractor.sampleTime) / totalDurationUs).toInt()
                        while (!sawInputEOS && nextBarSampleIndex == barSampleIndex) {
                            sawInputEOS = !extractor.advance()
                            if (!sawInputEOS) {
                                nextBarSampleIndex =
                                    (AudioWaveFormGenerator.SAMPLES_PER_BAR * (wave.size * extractor.sampleTime) / totalDurationUs).toInt()
                            }
                        }
                    }
                }
            }

            var outputBufferIndex: Int
            do {
                outputBufferIndex = codec.dequeueOutputBuffer(info, kTimeOutUs)
                if (outputBufferIndex >= 0) {
                    if (info.size > 0) {
                        noOutputCounter = 0
                    }

                    val buf = codec.getOutputBuffer(outputBufferIndex) ?: throw IOException("No output buffer")
                    val barIndex =
                        ((wave.size * info.presentationTimeUs) / totalDurationUs).toInt()
                    var total: Long = 0
                    var i = 0
                    while (i < info.size) {
                        val aShort = buf.getShort(i)
                        total += abs(aShort.toInt()).toLong()
                        i += 2 * 4
                    }
                    if (barIndex >= 0 && barIndex < wave.size) {
                        wave[barIndex] += total
                        waveSamples[barIndex] += info.size / 2
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Logger.d(tag = TAG) { "Output format has changed to " + codec.outputFormat }
                }
            } while (outputBufferIndex >= 0)
        }

        codec.stop()
        codec.release()
        extractor.release()

        val floats = FloatArray(AudioWaveFormGenerator.BAR_COUNT)
        val bytes = ByteArray(AudioWaveFormGenerator.BAR_COUNT)
        var max = 0f

        for (i in 0..<AudioWaveFormGenerator.BAR_COUNT) {
            if (waveSamples[i] == 0) continue

            floats[i] = wave[i] / waveSamples[i].toFloat()
            if (floats[i] > max) {
                max = floats[i]
            }
        }

        for (i in 0..<AudioWaveFormGenerator.BAR_COUNT) {
            val normalized = floats[i] / max
            bytes[i] = (255 * normalized).toInt().toByte()
        }
        return AudioFileInfo(totalDurationUs, bytes)
    }

    override fun saveWaveformToPng(amplitudes: FloatArray, width: Int, height: Int): ByteArray {
        val bitmap = createBitmap(width, height)
        val androidCanvas = android.graphics.Canvas(bitmap)

        val drawScope = CanvasDrawScope()
        drawScope.draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = androidx.compose.ui.graphics.Canvas(androidCanvas),
            size = Size(width.toFloat(), height.toFloat())
        ) {
            drawWaveform(amplitudes.toList())
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun createExtractor(file: PlatformFile): MediaExtractor {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.toString())
        return extractor
    }

    companion object {
        const val TAG = "AndroidWaveFormGenerator"
    }
}