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

// Short side of each rung and the target bitrate it needs.
private val sendRungs = listOf(
    1080 to 1_500_000, 720 to 800_000, 540 to 500_000, 360 to 300_000, 270 to 150_000, 180 to 0,
)

private const val STEP_UP_HEADROOM = 1.25

/** The rung to send at next: straight down to the rung [targetBps] fits, back up one rung at a time with headroom. */
internal fun nextSendHeight(currentHeight: Int, targetBps: Double): Int {
    val fits = sendRungs.first { (_, min) -> targetBps >= min }.first
    if (fits <= currentHeight) return fits
    val (up, upMin) = sendRungs.last { (height, _) -> height > currentHeight }
    return if (targetBps >= upMin * STEP_UP_HEADROOM) up else currentHeight
}

/**
 * Caps the sender's resolution to the network: libwebrtc's quality scaler only reacts to encoder QP and frame drops,
 * so a cheap-to-encode picture keeps 1080p at any target bitrate. [apply] receives scaleResolutionDownBy.
 */
internal class SendResolutionLadder(private val apply: (Double) -> Unit) {
    private var height = SendVideoCap.HEIGHT
    private var scale = 1.0

    fun onStats(stats: Collection<StatsEntry>): String? {
        val target = stats.video("outbound-rtp")?.get("targetBitrate").asDouble() ?: return null
        val source = stats.video("media-source")
        // iOS reports the camera's 4K before its adapter caps the encoder input at 1080p.
        val inputShortSide = listOfNotNull(source?.get("width").asDouble(), source?.get("height").asDouble())
            .filter { it > 0 }.plus(SendVideoCap.HEIGHT.toDouble()).min()
        val next = nextSendHeight(height, target)
        val nextScale = maxOf(1.0, inputShortSide / next)
        if (next == height && nextScale == scale) return null
        height = next
        scale = nextScale
        apply(nextScale)
        return "send ladder target=${target.toLong()} height=$next scaleDownBy=$nextScale"
    }
}
