package id.homebase.chat.services.calling

/** One entry of a WebRTC stats report, with its W3C type name ("inbound-rtp", "media-source", ...). */
internal class StatsEntry(val type: String, val members: Map<String, Any?>)

/**
 * The periodic call-health lines every engine logs. The media-source line counts frames the camera handed to the
 * sender, so a stalled outbound counter reads as a stopped camera (source frozen) or a failing encoder (source moving).
 */
internal fun videoStatsLines(stats: Collection<StatsEntry>, localTrack: String? = null): List<String> = buildList {
    fun video(type: String) = stats.firstOrNull { it.type == type && it.members["kind"] == "video" }?.members

    video("inbound-rtp")?.let { m ->
        add(
            "inbound video packets=${m["packetsReceived"]} lost=${m["packetsLost"]} bytes=${m["bytesReceived"]} " +
                "received=${m["framesReceived"]} decoded=${m["framesDecoded"]} dropped=${m["framesDropped"]} " +
                "fps=${m["framesPerSecond"]} freezes=${m["freezeCount"]} size=${m["frameWidth"]}x${m["frameHeight"]} plis=${m["pliCount"]} nacks=${m["nackCount"]} decoder=${m["decoderImplementation"]}",
        )
    }
    video("media-source")?.let { m ->
        add("source video frames=${m["frames"]} fps=${m["framesPerSecond"]} size=${m["width"]}x${m["height"]} track=$localTrack")
    }
    video("outbound-rtp")?.let { m ->
        add(
            "outbound video packets=${m["packetsSent"]} bytes=${m["bytesSent"]} encoded=${m["framesEncoded"]} " +
                "sent=${m["framesSent"]} keyframes=${m["keyFramesEncoded"]} size=${m["frameWidth"]}x${m["frameHeight"]} " +
                "fps=${m["framesPerSecond"]} target=${m["targetBitrate"]} limit=${m["qualityLimitationReason"]} " +
                "resChanges=${m["qualityLimitationResolutionChanges"]} plisIn=${m["pliCount"]} " +
                "firsIn=${m["firCount"]} nacksIn=${m["nackCount"]} encoder=${m["encoderImplementation"]}",
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
