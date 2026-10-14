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

fun lowLatencyVideoDecoderFactory(sharedContext: EglBase.Context?): VideoDecoderFactory =
    DefaultVideoDecoderFactory(LowLatencyHardwareDecoderFactory(sharedContext))

private class LowLatencyHardwareDecoderFactory(private val sharedContext: EglBase.Context?) : HardwareVideoDecoderFactory(null) {
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
        return ResizeResettingDecoder(info.name, type, colorFormat, sharedContext)
    }
}

// AndroidVideoDecoder's own resize path (reinitDecode) drops the old codec with frames still inside it but keeps their
// start times in frameInfos, which it pairs FIFO with later outputs: each leaked entry adds a frame interval to every
// decode time from then on (Redmi: +66 ms after a ladder climb), and VCMTiming adds that to the playout delay.
// release() is the only path that clears frameInfos, so a resize goes through release() + initDecode() instead.
private class ResizeResettingDecoder(
    codecName: String,
    type: VideoCodecMimeType,
    colorFormat: Int,
    sharedContext: EglBase.Context?,
) : AndroidVideoDecoder(LowLatencyCodecs, codecName, type, colorFormat, sharedContext) {
    private var settings: VideoDecoder.Settings? = null
    private var callback: VideoDecoder.Callback? = null

    override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
        this.settings = settings
        this.callback = callback
        return super.initDecode(settings, callback)
    }

    // libwebrtc's JNI wrapper passes a null DecodeInfo; a non-null Kotlin parameter throws into native and aborts.
    override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        val current = settings
        val callback = callback
        val w = frame.encodedWidth
        val h = frame.encodedHeight
        if (current != null && callback != null && w * h > 0 && (w != current.width || h != current.height)) {
            Logger.i("WebRtcKmp") { "decoder resize ${current.width}x${current.height} -> ${w}x$h: release + init" }
            release().takeIf { it != VideoCodecStatus.OK }?.let { return it }
            initDecode(VideoDecoder.Settings(current.numberOfCores, w, h), callback)
                .takeIf { it != VideoCodecStatus.OK }?.let { return it }
        }
        return super.decode(frame, info)
    }
}

private object LowLatencyCodecs : MediaCodecWrapperFactory {
    override fun createByCodecName(name: String): MediaCodecWrapper = LowLatencyCodec(MediaCodec.createByCodecName(name))
}

private class LowLatencyCodec(private val codec: MediaCodec) : MediaCodecWrapper {
    @Volatile private var queued = 0
    @Volatile private var output = 0
    override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        val mime = format.getString(MediaFormat.KEY_MIME)
        val keys = lowLatencyKeys(codec.codecInfo, mime)
        keys.forEach { (key, value) -> format.setInteger(key, value) }
        try {
            codec.configure(format, surface, crypto, flags)
            Logger.i("WebRtcKmp") { "decoder ${codec.name} configured $mime with $keys" }
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw e
            Logger.w(e, "WebRtcKmp") { "decoder ${codec.name} refused $keys; configuring without them" }
            codec.reset()
            keys.keys.forEach(format::removeKey)
            codec.configure(format, surface, crypto, flags)
        }
    }

    override fun start() = codec.start()
    override fun flush() = codec.flush()
    override fun stop() = codec.stop()
    override fun release() {
        Logger.i("WebRtcKmp") { "decoder ${codec.name} released after $queued inputs, $output outputs" }
        codec.release()
    }
    override fun dequeueInputBuffer(timeoutUs: Long) = codec.dequeueInputBuffer(timeoutUs)
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
        queued++
    }
    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long) =
        codec.dequeueOutputBuffer(info, timeoutUs).also { if (it >= 0) output++ }
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
    if (mime != null && info.supportsLowLatency(mime) == true) put(MediaFormat.KEY_LOW_LATENCY, 1)
    put(QTI_LOW_LATENCY, 1)
    put(QTI_PICTURE_ORDER, 1)
    put(MediaFormat.KEY_PRIORITY, 0)
}

/** Whether [mime] decoding offers FEATURE_LowLatency; null before Android 11, which cannot tell. */
internal fun MediaCodecInfo.supportsLowLatency(mime: String): Boolean? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null
    else runCatching { getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) }.getOrDefault(false)
