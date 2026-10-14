package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.BlockingDeque
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResizeResettingDecoderTest {
    @Test
    fun aResizeDropsTheTimingsOfFramesTheOldCodecStillHeld() {
        val codecs = HoldingCodecs()
        val decoder = ResizeResettingDecoder(codecs, "fake.decoder", VideoCodecMimeType.H264, COLOR_FORMAT)

        decodeHeldFramesThenResize(decoder)

        assertEquals(1, decoder.pendingFrameTimings())
        assertEquals(listOf(640 to 360, 1280 to 720), codecs.created.map { it.configuredSize })
        assertEquals(listOf(true, false), codecs.created.map { it.released })
        decoder.release()
    }

    // Pins the libwebrtc behaviour the wrapper exists for; if this starts failing, reinitDecode clears frameInfos itself.
    @Test
    fun theStockDecoderKeepsTheHeldFramesTimingsAcrossAResize() {
        val decoder = AndroidVideoDecoder(HoldingCodecs(), "fake.decoder", VideoCodecMimeType.H264, COLOR_FORMAT, null)

        decodeHeldFramesThenResize(decoder)

        assertEquals(HELD_FRAMES + 1, decoder.pendingFrameTimings())
        decoder.release()
    }

    private fun decodeHeldFramesThenResize(decoder: AndroidVideoDecoder) {
        assertEquals(VideoCodecStatus.OK, decoder.initDecode(VideoDecoder.Settings(1, 640, 360)) { _, _, _ -> })
        repeat(HELD_FRAMES) { assertEquals(VideoCodecStatus.OK, decoder.decode(frame(640, 360, key = it == 0), DECODE_INFO)) }
        assertEquals(HELD_FRAMES, decoder.pendingFrameTimings())
        assertEquals(VideoCodecStatus.OK, decoder.decode(frame(1280, 720, key = true), DECODE_INFO))
    }

    private fun AndroidVideoDecoder.pendingFrameTimings(): Int =
        (AndroidVideoDecoder::class.java.getDeclaredField("frameInfos").apply { isAccessible = true }.get(this) as BlockingDeque<*>).size

    private fun frame(width: Int, height: Int, key: Boolean): EncodedImage = EncodedImage.builder()
        .setBuffer(ByteBuffer.allocateDirect(16).apply { put(ByteArray(16) { 1 }).flip() }, null)
        .setEncodedWidth(width)
        .setEncodedHeight(height)
        .setFrameType(if (key) EncodedImage.FrameType.VideoFrameKey else EncodedImage.FrameType.VideoFrameDelta)
        .createEncodedImage()

    private class HoldingCodecs : MediaCodecWrapperFactory {
        val created = mutableListOf<HoldingCodec>()
        override fun createByCodecName(name: String): MediaCodecWrapper = HoldingCodec().also { created += it }
    }

    // Takes every input and never outputs, like a display-order decoder filling its picture buffer.
    private class HoldingCodec : MediaCodecWrapper {
        var configuredSize: Pair<Int, Int>? = null
        @Volatile var released = false

        override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
            configuredSize = format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        override fun start() = Unit
        override fun flush() = Unit
        override fun stop() = Unit
        override fun release() { released = true }
        override fun dequeueInputBuffer(timeoutUs: Long) = 0
        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) = Unit
        override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
            Thread.sleep(1)
            return MediaCodec.INFO_TRY_AGAIN_LATER
        }
        override fun releaseOutputBuffer(index: Int, render: Boolean) = Unit
        override fun getInputFormat(): MediaFormat = MediaFormat()
        override fun getOutputFormat(): MediaFormat = MediaFormat()
        override fun getOutputFormat(index: Int): MediaFormat = MediaFormat()
        override fun getInputBuffer(index: Int): ByteBuffer = ByteBuffer.allocate(1024)
        override fun getOutputBuffer(index: Int): ByteBuffer? = null
        override fun createInputSurface(): Surface = error("not used")
        override fun setParameters(params: Bundle) = Unit
        override fun getCodecInfo(): MediaCodecInfo = error("not used")
    }

    private companion object {
        const val HELD_FRAMES = 5
        const val COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val DECODE_INFO = VideoDecoder.DecodeInfo(false, 0)
    }
}
