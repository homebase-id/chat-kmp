package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import co.touchlab.kermit.Logger
import java.nio.ByteBuffer

// libwebrtc configures MediaCodec with only a color format, and in its default display-order mode the Qualcomm decoder
// fills its picture buffer before it outputs a frame. AndroidVideoDecoder and MediaCodecWrapperFactory are
// package-private, hence this package: a libwebrtc bump that renames them breaks the build here, not at runtime.

fun lowLatencyVideoDecoderFactory(): VideoDecoderFactory = DefaultVideoDecoderFactory(LowLatencyHardwareDecoderFactory())

private class LowLatencyHardwareDecoderFactory : HardwareVideoDecoderFactory(null) {
    override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
        val type = VideoCodecMimeType.valueOf(codecInfo.name)
        val candidates = (0 until MediaCodecList.getCodecCount())
            .mapNotNull { runCatching { MediaCodecList.getCodecInfoAt(it) }.getOrNull() }
            .filter { !it.isEncoder && MediaCodecUtils.codecSupportsType(it, type) && !MediaCodecUtils.isSoftwareOnly(it) }
            .mapNotNull { info ->
                val colorFormat = MediaCodecUtils.selectColorFormat(
                    MediaCodecUtils.DECODER_COLOR_FORMATS,
                    info.getCapabilitiesForType(type.mimeType()),
                ) ?: return@mapNotNull null
                info to colorFormat
            }
        val (info, colorFormat) = candidates.firstOrNull { (info, _) -> info.name.endsWith(".low_latency") }
            ?: candidates.firstOrNull()
            ?: return null
        return AndroidVideoDecoder(LowLatencyCodecs, info.name, type, colorFormat, null)
    }
}

private object LowLatencyCodecs : MediaCodecWrapperFactory {
    override fun createByCodecName(name: String): MediaCodecWrapper = LowLatencyCodec(MediaCodec.createByCodecName(name))
}

private class LowLatencyCodec(private val codec: MediaCodec) : MediaCodecWrapper {
    override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        val keys = lowLatencyKeys(codec.codecInfo, format.getString(MediaFormat.KEY_MIME))
        keys.forEach { (key, value) -> format.setInteger(key, value) }
        try {
            codec.configure(format, surface, crypto, flags)
            Logger.i("WebRtcKmp") { "decoder ${codec.name} configured ${format.getString(MediaFormat.KEY_MIME)} with $keys" }
        } catch (e: RuntimeException) {
            if (keys.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw e
            Logger.w(e, "WebRtcKmp") { "decoder ${codec.name} refused $keys; configuring without them" }
            codec.reset()
            keys.keys.forEach(format::removeKey)
            codec.configure(format, surface, crypto, flags)
        }
    }

    override fun start() = codec.start()
    override fun flush() = codec.flush()
    override fun stop() = codec.stop()
    override fun release() = codec.release()
    override fun dequeueInputBuffer(timeoutUs: Long) = codec.dequeueInputBuffer(timeoutUs)
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) =
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long) = codec.dequeueOutputBuffer(info, timeoutUs)
    override fun releaseOutputBuffer(index: Int, render: Boolean) = codec.releaseOutputBuffer(index, render)
    override fun getInputFormat(): MediaFormat = codec.inputFormat
    override fun getOutputFormat(): MediaFormat = codec.outputFormat
    override fun getOutputFormat(index: Int): MediaFormat = codec.getOutputFormat(index)
    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)
    override fun createInputSurface(): Surface = codec.createInputSurface()
    override fun setParameters(params: Bundle) = codec.setParameters(params)
    override fun getCodecInfo(): MediaCodecInfo = codec.codecInfo
}

private const val QTI_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
private const val QTI_PICTURE_ORDER = "vendor.qti-ext-dec-picture-order.enable"

// Codecs ignore keys they do not know; the Qualcomm ones put the decoder in decode order, outputting each frame at once.
private fun lowLatencyKeys(info: MediaCodecInfo, mime: String?): Map<String, Int> = buildMap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mime != null &&
        runCatching { info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) }.getOrDefault(false)
    ) {
        put(MediaFormat.KEY_LOW_LATENCY, 1)
    }
    put(QTI_LOW_LATENCY, 1)
    put(QTI_PICTURE_ORDER, 1)
    put(MediaFormat.KEY_PRIORITY, 0)
}
