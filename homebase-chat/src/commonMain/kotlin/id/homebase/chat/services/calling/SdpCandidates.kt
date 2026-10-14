package id.homebase.chat.services.calling

data class LocalCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?)

object SdpCandidates {
    /** Returns [sdp] with each candidate appended to its media section, matched by `a=mid` first, then by m-line index. */
    fun withCandidates(sdp: String, candidates: List<LocalCandidate>): String {
        if (candidates.isEmpty()) return sdp
        val eol = if ("\r\n" in sdp) "\r\n" else "\n"
        val lines = sdp.split(eol).filter { it.isNotEmpty() }.toMutableList()
        val sectionStarts = lines.indices.filter { lines[it].startsWith("m=") }
        if (sectionStarts.isEmpty()) return sdp
        val inserts = mutableMapOf<Int, MutableList<String>>()
        for (c in candidates) {
            val line = candidateLine(c.candidate) ?: continue
            val section = sectionStarts.indices.firstOrNull { i -> midOf(lines, sectionStarts, i) == c.sdpMid && c.sdpMid != null }
                ?: c.sdpMLineIndex?.takeIf { it in sectionStarts.indices }
                ?: continue
            if (line in sectionLines(lines, sectionStarts, section)) continue
            inserts.getOrPut(section) { mutableListOf() }.let { if (line !in it) it += line }
        }
        if (inserts.isEmpty()) return sdp
        for (section in inserts.keys.sortedDescending()) {
            val end = if (section + 1 < sectionStarts.size) sectionStarts[section + 1] else lines.size
            lines.addAll(end, inserts.getValue(section))
        }
        return lines.joinToString(eol) + eol
    }

    private fun candidateLine(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("a=") -> trimmed
            trimmed.startsWith("candidate:") -> "a=$trimmed"
            else -> null
        }
    }

    private fun sectionLines(lines: List<String>, starts: List<Int>, section: Int): List<String> {
        val end = if (section + 1 < starts.size) starts[section + 1] else lines.size
        return lines.subList(starts[section], end)
    }

    private fun midOf(lines: List<String>, starts: List<Int>, section: Int): String? =
        sectionLines(lines, starts, section).firstOrNull { it.startsWith("a=mid:") }?.removePrefix("a=mid:")?.trim()
}
