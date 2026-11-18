package id.homebase.api.video.hls

/** What our TS writer and reader share: the packet, the stream types and the H.264 NAL types they handle. */
internal object MpegTs {
    const val PACKET = 188
    const val STREAM_H264 = 0x1B
    const val STREAM_AAC_ADTS = 0x0F
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9
    const val AAC_FRAME = 1024
}
