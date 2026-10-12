package id.homebase.chat.services.calling

/**
 * What a sender may put on the wire: at most 1080p30, adapted down by WebRTC's bandwidth estimate. libwebrtc offers
 * H.264 at level 3.1 (profile-level-id 42e01f, at most 3600 macroblocks a frame) and each side encodes at the level
 * the peer advertised, so every SDP we hand out is raised to level 4.0 first: VideoToolbox rejects a 1080p frame at
 * 3.1 with -12902 and, with no fallback codec, the stream stops for good.
 */
internal object SendVideoCap {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val FPS = 30
    const val MAX_BITRATE_BPS = 2_500_000

    // libwebrtc has no resolution floor; a floor bitrate keeps the quality scaler from dropping below ~480p for long.
    const val MIN_BITRATE_BPS = 300_000

    // Level 4.0: 8192 macroblocks a frame, 245760 a second; 1080p30 needs 8160 and 244800.
    const val H264_MIN_LEVEL = 0x28
}

internal fun h264MacroblocksPerFrame(width: Int, height: Int): Int = ((width + 15) / 16) * ((height + 15) / 16)

private val profileLevelId = Regex("(profile-level-id=)([0-9a-fA-F]{4})([0-9a-fA-F]{2})")

/** Raises every H.264 profile-level-id in [sdp] below [SendVideoCap.H264_MIN_LEVEL] to it; profiles are untouched. */
internal fun withH264SendLevel(sdp: String): String = profileLevelId.replace(sdp) { m ->
    val (key, profile, level) = m.destructured
    if (level.toInt(16) >= SendVideoCap.H264_MIN_LEVEL) m.value
    else key + profile + SendVideoCap.H264_MIN_LEVEL.toString(16).padStart(2, '0')
}

internal fun h264Levels(sdp: String): List<Int> = profileLevelId.findAll(sdp).map { it.groupValues[3].toInt(16) }.toList()
