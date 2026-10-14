package id.homebase.chat.services.calling

import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// At most 720p30, adapted down by WebRTC's bandwidth estimate. It must fit H.264 level 3.1, which libwebrtc offers and
// each side encodes at (3600 macroblocks a frame, 108000 a second): VideoToolbox rejects a larger frame with -12902.
internal object SendVideoCap {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30
    const val MAX_BITRATE_BPS = 2_000_000

    // libwebrtc has no resolution floor; a floor bitrate keeps the quality scaler from dropping below ~480p for long.
    const val MIN_BITRATE_BPS = 300_000
}

// Short side of each rung and the target bitrate it needs; no floor.
private val sendRungs = listOf(720 to 800_000, 540 to 500_000, 360 to 250_000, 270 to 150_000, 180 to 0)

private const val STEP_UP_HEADROOM = 1.25

/** The rung to send at next: straight down to the rung [targetBps] fits, back up one rung at a time with headroom. */
internal fun nextSendHeight(currentHeight: Int, targetBps: Double): Int {
    val fits = sendRungs.first { (_, min) -> targetBps >= min }.first
    if (fits <= currentHeight) return fits
    val (up, upMin) = sendRungs.last { (height, _) -> height > currentHeight }
    return if (targetBps >= upMin * STEP_UP_HEADROOM) up else currentHeight
}

/**
 * The largest short side up to [target] at which both sides of a [short]x[long] input scale to even integers: an Android
 * hardware encoder fails on an odd width (1280x720 at 480 is 853 wide) and libwebrtc then drops H.264 for software VP8.
 */
internal fun evenShortSide(target: Int, short: Int, long: Int): Int = (target downTo 2).firstOrNull { t ->
    t % 2 == 0 && long.toLong() * t % short == 0L && long.toLong() * t / short % 2 == 0L
} ?: target

// goog-cc raises its estimate only to ~1.5x what is acked, so a sender this far under target holds the target down.
private const val ENCODER_LIMITED_RATIO = 1.5

// Sending this close to target means the network, not the encoder, sets the rate.
private const val NETWORK_BOUND_RATIO = 1.25
private const val CLEAN_LOSS = 0.02
private const val CLEAN_RTT_SLACK_S = 0.05
private const val CLIMB_TICKS = 3
private const val CLIMB_PROBATION_TICKS = CLIMB_TICKS + 1
private const val MAX_CLIMB_TICKS = 24

/**
 * Caps the sender's resolution to the network: libwebrtc's quality scaler only reacts to encoder QP and frame drops,
 * so a cheap-to-encode picture keeps the top rung at any target bitrate. It steps down only when the network sets the rate.
 * A static picture sends far under target and goog-cc never raises the target to the next rung, so the ladder also
 * climbs a rung after [CLIMB_TICKS] stats ticks of that on a clean link (no loss, RTT near the call's best); a climb
 * the network then refuses doubles that wait. [SendLoadRung] lowers the cap further when the device cannot encode
 * 30 fps. [apply] receives scaleResolutionDownBy.
 */
internal class SendResolutionLadder(
    private val clock: TimeSource = TimeSource.Monotonic,
    private val applyFps: (Int) -> Unit = {},
    private val apply: (Double) -> Unit,
) {
    @Volatile
    private var pressure = DevicePressure()

    private var height = SendVideoCap.HEIGHT
    private var scale = 1.0
    private var appliedFps = SendVideoCap.FPS
    private val load = SendLoadRung()
    private var lastLoadHeight = SendVideoCap.HEIGHT
    private var lastBytesSent: Pair<Double, TimeMark>? = null
    private var minRtt = Double.MAX_VALUE
    private var climbTicksNeeded = CLIMB_TICKS
    private var encoderLimitedTicks = 0
    private var ticksSinceEncoderClimb: Int? = null

    val fps: Int get() = pressure.cap.fps

    fun setPressure(next: DevicePressure) {
        // Only stored: the sender's parameters are read-modify-written, so every write stays on the stats loop.
        pressure = next
    }

    fun onStats(stats: Collection<StatsEntry>): String? {
        val outbound = stats.video("outbound-rtp") ?: return null
        val target = outbound["targetBitrate"].asDouble() ?: return null
        val cap = pressure.cap
        if (cap.fps != appliedFps) {
            appliedFps = cap.fps
            applyFps(cap.fps)
        }
        val sent = sentBps(outbound["bytesSent"].asDouble())
        val clean = linkIsClean(stats)
        val source = stats.video("media-source")
        val sides = listOfNotNull(source?.get("width").asDouble(), source?.get("height").asDouble()).filter { it > 0 }.sorted()
        val (sourceShort, sourceLong) = if (sides.size == 2) sides[0] to sides[1] else SendVideoCap.HEIGHT.toDouble() to SendVideoCap.WIDTH.toDouble()
        // iOS reports the camera's 4K before its adapter caps the encoder input at 720p.
        val inputShortSide = minOf(sourceShort, SendVideoCap.HEIGHT.toDouble())
        val inputLongSide = (sourceLong * inputShortSide / sourceShort).roundToInt()
        val loadBefore = lastLoadHeight
        val loadHeight = minOf(load.onStats(outbound), cap.height)
        val next = nextHeight(target, sent, clean, outbound, minOf(height, loadHeight))
        val nextScale = maxOf(1.0, inputShortSide / evenShortSide(minOf(next, loadHeight), inputShortSide.roundToInt(), inputLongSide))
        if (next == height && nextScale == scale && loadHeight == loadBefore) return null
        height = next
        lastLoadHeight = loadHeight
        if (nextScale != scale) apply(nextScale)
        scale = nextScale
        return "send ladder target=${target.toLong()} sent=${sent?.toLong()} clean=$clean height=$next load=$loadHeight " +
            "climbTicks=$climbTicksNeeded scaleDownBy=$nextScale"
    }

    private fun nextHeight(target: Double, sent: Double?, clean: Boolean, outbound: Map<String, Any?>, sending: Int): Int {
        ticksSinceEncoderClimb = ticksSinceEncoderClimb?.plus(1)
        val byTarget = nextSendHeight(height, target)
        val networkBound = sent == null || !clean || sent * NETWORK_BOUND_RATIO >= target
        if (byTarget < height && networkBound) {
            encoderLimitedTicks = 0
            // The rung an encoder-limited climb reached was more than the link carries: wait longer before the next try.
            if ((ticksSinceEncoderClimb ?: Int.MAX_VALUE) <= CLIMB_PROBATION_TICKS) {
                climbTicksNeeded = minOf(climbTicksNeeded * 2, MAX_CLIMB_TICKS)
            }
            ticksSinceEncoderClimb = null
            return byTarget
        }
        if (byTarget > height) {
            encoderLimitedTicks = 0
            return byTarget
        }
        val up = sendRungs.lastOrNull { (h, _) -> h > height }?.first ?: return height
        val encoderLimited = sent != null && clean && sent * ENCODER_LIMITED_RATIO <= target &&
            outbound["qualityLimitationReason"] == "none" && sendsFullRung(outbound, sending)
        encoderLimitedTicks = if (encoderLimited) encoderLimitedTicks + 1 else 0
        if (encoderLimitedTicks < climbTicksNeeded) return height
        encoderLimitedTicks = 0
        if ((ticksSinceEncoderClimb ?: 0) > CLIMB_PROBATION_TICKS) climbTicksNeeded = CLIMB_TICKS
        ticksSinceEncoderClimb = 0
        return up
    }

    // libwebrtc shedding resolution on its own (BALANCED, bandwidth) also sends under target; climbing then flaps.
    private fun sendsFullRung(outbound: Map<String, Any?>, sending: Int): Boolean {
        val short = outbound.sentShortSide() ?: return false
        return short >= sending * 0.9
    }

    private fun linkIsClean(stats: Collection<StatsEntry>): Boolean {
        val remote = stats.video("remote-inbound-rtp")
        val pair = stats.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }?.members
        val rtt = remote?.get("roundTripTime").asDouble() ?: pair?.get("currentRoundTripTime").asDouble() ?: return false
        minRtt = minOf(minRtt, rtt)
        val loss = remote?.get("fractionLost").asDouble() ?: 0.0
        return loss <= CLEAN_LOSS && rtt <= minRtt * 2 + CLEAN_RTT_SLACK_S
    }

    private fun sentBps(bytesSent: Double?): Double? {
        val previous = lastBytesSent
        lastBytesSent = bytesSent?.let { it to clock.markNow() }
        if (bytesSent == null || previous == null) return null
        val seconds = previous.second.elapsedNow().inWholeMilliseconds / 1000.0
        return if (seconds > 0) (bytesSent - previous.first) * 8 / seconds else null
    }
}

private val loadRungs = sendRungs.map { it.first }

private fun Map<String, Any?>.sentShortSide(): Double? = listOfNotNull(this["frameWidth"].asDouble(), this["frameHeight"].asDouble()).minOrNull()

private const val SLOW_FPS = SendVideoCap.FPS * 0.8
private const val FAST_FPS = SendVideoCap.FPS * 0.9
private const val SLOW_ENCODE_MS = 1500.0 / SendVideoCap.FPS
private const val STEP_UP_ENCODE_MS = SLOW_ENCODE_MS * 0.8
private const val SLOW_TICKS_TO_STEP_DOWN = 2
private const val FAST_TICKS_TO_STEP_UP = 6

// libwebrtc's CPU adaptation waits for 200 % encode usage on a hardware encoder; slow = under 24 fps with frames over
// 50 ms to encode, so a camera slowed by low light never counts.
internal class SendLoadRung {
    var height = SendVideoCap.HEIGHT
        private set
    private var previous: Map<String, Any?> = emptyMap()
    private var slowTicks = 0
    private var fastTicks = 0

    fun onStats(outbound: Map<String, Any?>): Int {
        val encodeMs = perFrame(outbound, previous, "totalEncodeTime", "framesEncoded", 1000.0)
        previous = outbound
        val fps = outbound["framesPerSecond"].asDouble()
        val sentShortSide = outbound.sentShortSide()
        if (fps == null || encodeMs == null || sentShortSide == null || sentShortSide <= 0) {
            slowTicks = 0
            fastTicks = 0
            return height
        }
        if (fps < SLOW_FPS && encodeMs > SLOW_ENCODE_MS) {
            fastTicks = 0
            if (++slowTicks >= SLOW_TICKS_TO_STEP_DOWN) {
                slowTicks = 0
                height = loadRungs.firstOrNull { it < minOf(height.toDouble(), sentShortSide) } ?: height
            }
            return height
        }
        slowTicks = 0
        val up = loadRungs.lastOrNull { it > height }
        val ratio = (up ?: 0) / sentShortSide
        if (up != null && fps >= FAST_FPS && encodeMs * ratio * ratio <= STEP_UP_ENCODE_MS) {
            if (++fastTicks >= FAST_TICKS_TO_STEP_UP) {
                fastTicks = 0
                height = up
            }
        } else {
            fastTicks = 0
        }
        return height
    }
}
