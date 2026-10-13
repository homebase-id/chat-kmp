package id.homebase.chat.services.calling

import kotlin.math.roundToLong

/** One entry of a WebRTC stats report, with its W3C type name ("inbound-rtp", "media-source", ...). */
internal class StatsEntry(val type: String, val members: Map<String, Any?>)

/**
 * The periodic call-health lines every engine logs. The media-source line counts frames the camera handed to the
 * sender, so a stalled outbound counter reads as a stopped camera (source frozen) or a failing encoder (source moving).
 * The per-frame averages (qp, encodeMs, decodeMs, jbMs) cover the interval since [previous]: a QP above the quality
 * scaler's threshold with limit=none means the sender is not adapting, and decodeMs above 1000/fps means the receiver's
 * decoder cannot keep up.
 */
internal fun videoStatsLines(
    stats: Collection<StatsEntry>,
    localTrack: String? = null,
    previous: Collection<StatsEntry> = emptyList(),
): List<String> = buildList {
    stats.video("inbound-rtp")?.let { m ->
        val before = previous.video("inbound-rtp").orEmpty()
        add(
            "inbound video packets=${m["packetsReceived"]} lost=${m["packetsLost"]} bytes=${m["bytesReceived"]} " +
                "received=${m["framesReceived"]} decoded=${m["framesDecoded"]} dropped=${m["framesDropped"]} " +
                "fps=${m["framesPerSecond"]} freezes=${m["freezeCount"]} size=${m["frameWidth"]}x${m["frameHeight"]} plis=${m["pliCount"]} nacks=${m["nackCount"]} decoder=${m["decoderImplementation"]} " +
                "qp=${perFrame(m, before, "qpSum", "framesDecoded")} " +
                "decodeMs=${perFrame(m, before, "totalDecodeTime", "framesDecoded", 1000.0)} " +
                "jbMs=${perFrame(m, before, "jitterBufferDelay", "jitterBufferEmittedCount", 1000.0)} " +
                "powerEfficient=${m["powerEfficientDecoder"]}",
        )
    }
    stats.video("media-source")?.let { m ->
        add("source video frames=${m["frames"]} fps=${m["framesPerSecond"]} size=${m["width"]}x${m["height"]} track=$localTrack")
    }
    stats.video("outbound-rtp")?.let { m ->
        val before = previous.video("outbound-rtp").orEmpty()
        add(
            "outbound video packets=${m["packetsSent"]} bytes=${m["bytesSent"]} encoded=${m["framesEncoded"]} " +
                "sent=${m["framesSent"]} keyframes=${m["keyFramesEncoded"]} size=${m["frameWidth"]}x${m["frameHeight"]} " +
                "fps=${m["framesPerSecond"]} target=${m["targetBitrate"]} limit=${m["qualityLimitationReason"]} " +
                "resChanges=${m["qualityLimitationResolutionChanges"]} plisIn=${m["pliCount"]} " +
                "firsIn=${m["firCount"]} nacksIn=${m["nackCount"]} encoder=${m["encoderImplementation"]} " +
                "qp=${perFrame(m, before, "qpSum", "framesEncoded")} " +
                "encodeMs=${perFrame(m, before, "totalEncodeTime", "framesEncoded", 1000.0)} " +
                "limitDurations=${m["qualityLimitationDurations"]}",
        )
    }
    stats.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
        ?.members?.let { m ->
            add(
                "pair sent=${m["bytesSent"]} received=${m["bytesReceived"]} rtt=${m["currentRoundTripTime"]} " +
                    "outBitrate=${m["availableOutgoingBitrate"]} local=${m["localCandidateId"]} remote=${m["remoteCandidateId"]}",
            )
        }
}

internal fun Collection<StatsEntry>.video(type: String) = firstOrNull { it.type == type && it.members["kind"] == "video" }?.members

// Android reports uint64 counters as BigInteger and iOS as NSNumber; both read through toString.
internal fun Any?.asDouble(): Double? = when (this) {
    null -> null
    is Number -> toDouble()
    else -> toString().toDoubleOrNull()
}

private fun perFrame(now: Map<String, Any?>, before: Map<String, Any?>, total: String, frames: String, scale: Double = 1.0): Double? {
    val totalNow = now[total].asDouble() ?: return null
    val frameCount = (now[frames].asDouble() ?: return null) - (before[frames].asDouble() ?: 0.0)
    if (frameCount <= 0) return null
    val average = (totalNow - (before[total].asDouble() ?: 0.0)) * scale / frameCount
    return (average * 10).roundToLong() / 10.0
}
