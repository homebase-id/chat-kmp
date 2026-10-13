package id.homebase.chat.services.calling

import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.WrappedNativeVideoEncoder

/**
 * libwebrtc rounds a scaleResolutionDownBy layer to input / scale with no alignment, so the ladder's 480p rung
 * turns the quality scaler's 960x540 into 427x240. HardwareVideoEncoder rejects an odd size with ERR_SIZE after releasing its codec, and
 * H.264 has no software fallback on Android, so the stream stopped for the rest of the call. This drops the odd row or
 * column before the codec sees it.
 */
internal class EvenSizeVideoEncoderFactory(private val delegate: VideoEncoderFactory) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? =
        delegate.createEncoder(info)?.let { if (it is WrappedNativeVideoEncoder) it else EvenSizeVideoEncoder(it) }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs

    override fun getImplementations(): Array<VideoCodecInfo> = delegate.implementations

    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = delegate.encoderSelector
}

private class EvenSizeVideoEncoder(private val delegate: VideoEncoder) : VideoEncoder by delegate {
    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        val even = with(settings) {
            VideoEncoder.Settings(
                numberOfCores, evenFloor(width), evenFloor(height), startBitrate, maxFramerate,
                numberOfSimulcastStreams, automaticResizeOn, capabilities,
            )
        }
        return delegate.initEncode(even, callback)
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val buffer = frame.buffer
        val width = evenFloor(buffer.width)
        val height = evenFloor(buffer.height)
        if (width == buffer.width && height == buffer.height) return delegate.encode(frame, info)
        // The hardware encoder draws or copies the frame before encode returns, so it can be released right after.
        val even = VideoFrame(buffer.cropAndScale(0, 0, width, height, width, height), frame.rotation, frame.timestampNs)
        return try {
            delegate.encode(even, info)
        } finally {
            even.release()
        }
    }

    override fun isHardwareEncoder(): Boolean = delegate.isHardwareEncoder

    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = delegate.encoderInfo

    override fun setRates(parameters: VideoEncoder.RateControlParameters): VideoCodecStatus = delegate.setRates(parameters)

    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = delegate.resolutionBitrateLimits
}
