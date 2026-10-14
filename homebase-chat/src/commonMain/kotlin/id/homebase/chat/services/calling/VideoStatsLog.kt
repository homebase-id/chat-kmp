package id.homebase.chat.services.calling

import kotlin.math.roundToLong

/** One entry of a WebRTC stats report, with its W3C type name ("inbound-rtp", "media-source", ...). */
internal class StatsEntry(val type: String, val members: Map<String, Any?>, val id: String = "")

/** The periodic call-health lines every engine logs; per-frame averages cover the interval since [previous]. */
internal fun videoStatsLines(
    stats: Collection<StatsEntry>,
    localTrack: String? = null,
    previous: Collection<StatsEntry> = emptyList(),
): List<String> = buildList {
    stats.video("inbound-rtp")?.let { m ->
        val before = previous.video("inbound-rtp").orEmpty()
        val decodeMs = perFrame(m, before, "totalDecodeTime", "framesDecoded", 1000.0)
        add(
            "inbound video codec=${stats.codecOf(m)} packets=${m["packetsReceived"]} lost=${m["packetsLost"]} bytes=${m["bytesReceived"]} " +
                "received=${m["framesReceived"]} decoded=${m["framesDecoded"]} dropped=${m["framesDropped"]} " +
                "fps=${m["framesPerSecond"]} freezes=${m["freezeCount"]} freezeS=${m["totalFreezesDuration"]} size=${m["frameWidth"]}x${m["frameHeight"]} plis=${m["pliCount"]} nacks=${m["nackCount"]} decoder=${m["decoderImplementation"]} " +
                "qp=${perFrame(m, before, "qpSum", "framesDecoded")} " +
                "decodeMs=$decodeMs inFlight=${framesInFlight(decodeMs, m["framesPerSecond"].asDouble())} " +
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
            "outbound video codec=${stats.codecOf(m)} packets=${m["packetsSent"]} bytes=${m["bytesSent"]} encoded=${m["framesEncoded"]} " +
                "encodedDelta=${delta(m, before, "framesEncoded")} " +
                "sent=${m["framesSent"]} keyframes=${m["keyFramesEncoded"]} size=${m["frameWidth"]}x${m["frameHeight"]} " +
                "fps=${m["framesPerSecond"]} target=${m["targetBitrate"]} limit=${m["qualityLimitationReason"]} " +
                "resChanges=${m["qualityLimitationResolutionChanges"]} plisIn=${m["pliCount"]} " +
                "firsIn=${m["firCount"]} nacksIn=${m["nackCount"]} encoder=${m["encoderImplementation"]} " +
                "qp=${perFrame(m, before, "qpSum", "framesEncoded")} " +
                "encodeMs=${perFrame(m, before, "totalEncodeTime", "framesEncoded", 1000.0)} " +
                "limitDurations=${m["qualityLimitationDurations"]}",
        )
    }
    stats.media("inbound-rtp", "audio")?.let { m ->
        val before = previous.media("inbound-rtp", "audio").orEmpty()
        add(
            "inbound audio codec=${stats.codecOf(m)} jbMs=${perFrame(m, before, "jitterBufferDelay", "jitterBufferEmittedCount", 1000.0)} " +
                "jbTargetMs=${perFrame(m, before, "jitterBufferTargetDelay", "jitterBufferEmittedCount", 1000.0)} " +
                "concealed=${delta(m, before, "concealedSamples")}",
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

internal fun Collection<StatsEntry>.video(type: String) = media(type, "video")

private fun Collection<StatsEntry>.media(type: String, kind: String) = firstOrNull { it.type == type && it.members["kind"] == kind }?.members

private fun Collection<StatsEntry>.codecOf(rtp: Map<String, Any?>): Any? =
    firstOrNull { it.type == "codec" && it.id == rtp["codecId"] }?.members?.get("mimeType")

private fun framesInFlight(decodeMs: Double?, fps: Double?): Double? {
    if (decodeMs == null || fps == null) return null
    return (decodeMs * fps / 1000).oneDecimal()
}

// Android reports uint64 counters as BigInteger and iOS as NSNumber; both read through toString.
internal fun Any?.asDouble(): Double? = when (this) {
    null -> null
    is Number -> toDouble()
    else -> toString().toDoubleOrNull()
}

private fun delta(now: Map<String, Any?>, before: Map<String, Any?>, counter: String): Long? {
    val value = now[counter].asDouble() ?: return null
    return (value - (before[counter].asDouble() ?: 0.0)).toLong()
}

internal fun perFrame(now: Map<String, Any?>, before: Map<String, Any?>, total: String, frames: String, scale: Double = 1.0): Double? {
    val totalNow = now[total].asDouble() ?: return null
    val frameCount = (now[frames].asDouble() ?: return null) - (before[frames].asDouble() ?: 0.0)
    if (frameCount <= 0) return null
    val average = (totalNow - (before[total].asDouble() ?: 0.0)) * scale / frameCount
    return average.oneDecimal()
}

private fun Double.oneDecimal() = (this * 10).roundToLong() / 10.0
