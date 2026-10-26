package id.homebase.chat.services.calling

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object CallLogReport {
    private const val TAIL_MS = 5_000L
    private const val MAX_LINES = 4_000
    private val CALL_TAGS = listOf("Call", "WebRtc", "WebRTC", "PeerLink")
    private val LINE = Regex("""^(\d{4}-\d\d-\d\dT[\d:.]+Z) \w+: \(([^)]*)\) """)
    private val OTHER_CALL = Regex("""call=([\w-]+)""")
    private val SECRET = Regex("""(?i)\b(ice-pwd|ice-ufrag|fingerprint|secret|password|token|key)(\s*[=:]\s*)\S+""")

    private class Entry(val at: Instant, val tag: String, val lines: MutableList<String>) {
        val text: String get() = lines.joinToString("\n")
    }

    fun exportName(callId: String): String = "call-report-${callId.filter { it.isLetterOrDigit() || it == '-' }.take(36)}.txt"

    /** The lines of [callId]'s lifecycle in time order: those naming it, plus the call-tagged engine lines between its first and last, minus other calls'. */
    fun select(files: List<List<String>>, callId: String): List<String> {
        if (callId.isBlank()) return emptyList()
        val entries = files.flatMap(::parse).sortedBy { it.at }
        val anchors = entries.filter { callId in it.text }
        if (anchors.isEmpty()) return emptyList()
        val from = anchors.first().at
        val to = anchors.last().at + TAIL_MS.milliseconds
        return entries
            .filter { e ->
                callId in e.text ||
                    (e.at in from..to && CALL_TAGS.any { e.tag.startsWith(it) } && OTHER_CALL.findAll(e.text).all { it.groupValues[1] == callId })
            }
            .flatMap { it.lines }
            .map { it.replace(SECRET) { m -> "${m.groupValues[1]}${m.groupValues[2]}[redacted]" } }
            .takeLast(MAX_LINES)
    }

    fun render(header: List<String>, lines: List<String>): String =
        (header + "" + lines).joinToString("\n", postfix = "\n")

    private fun parse(file: List<String>): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (line in file) {
            val match = LINE.find(line)
            val at = match?.let { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() }
            if (match != null && at != null) entries += Entry(at, match.groupValues[2], mutableListOf(line))
            else entries.lastOrNull()?.lines?.add(line)
        }
        return entries
    }
}
