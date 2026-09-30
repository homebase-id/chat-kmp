package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException

private const val PROCESSED_CAP = 1000
const val SLOW_POLL_MS = 5_000L

class ProcessedStore(private val file: File?, private val cap: Int = PROCESSED_CAP) {
    private val ids = LinkedHashSet<String>()
    private var diskLines = 0

    init {
        file?.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.let { lines ->
            ids.addAll(lines.takeLast(cap))
            diskLines = lines.size
            if (diskLines > ids.size) compact()
        }
    }

    operator fun contains(id: Uuid) = id.toString() in ids

    fun add(id: Uuid) {
        if (!ids.add(id.toString())) return
        while (ids.size > cap) ids.remove(ids.first())
        file?.appendText("$id\n")
        if (++diskLines > 2 * cap) compact()
    }

    private fun compact() {
        file?.writeText(ids.joinToString("\n", postfix = "\n"))
        diskLines = ids.size
    }

    val size get() = ids.size
}

class RunLimiter(
    private val file: File?,
    private val perHour: Int,
    private val perDay: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Run(val at: Long, val authors: Set<String>)

    private val runs = ArrayList<Run>()
    private var diskLines = 0

    init {
        val cutoff = now() - DAY_MS
        file?.takeIf { it.exists() }?.readLines()?.forEach {
            diskLines++
            val at = it.substringBefore('\t').toLongOrNull() ?: return@forEach
            if (at >= cutoff) runs += Run(at, it.substringAfter('\t', "").split(',').toSet())
        }
        if (diskLines > runs.size) compact()
    }

    fun allows(authors: Set<String>): Boolean {
        val t = now()
        runs.removeAll { it.at < t - DAY_MS }
        if (runs.size >= perDay) return false
        return authors.all { a -> runs.count { it.at >= t - HOUR_MS && a in it.authors } < perHour }
    }

    fun record(authors: Set<String>) {
        val run = Run(now(), authors)
        runs += run
        file?.appendText(line(run))
        if (++diskLines > 2 * runs.size + COMPACT_SLACK) compact()
    }

    private fun line(run: Run) = "${run.at}\t${run.authors.joinToString(",")}\n"

    private fun compact() {
        file?.writeText(runs.joinToString("") { line(it) })
        diskLines = runs.size
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        const val COMPACT_SLACK = 100
    }
}

class ReadReceipts(
    private val enabled: Boolean,
    private val self: OdinId,
    private val allowlist: Allowlist,
    private val store: ProcessedStore,
    private val send: suspend (List<Uuid>) -> Unit,
    private val log: (String) -> Unit,
) {
    private val failed = LinkedHashMap<Uuid, Uuid>()

    suspend fun mark(msgs: List<ChatMsg>) {
        if (!enabled) return
        val todo = LinkedHashMap<Uuid, Uuid>(failed)
        for (m in msgs) {
            val fileId = m.fileId ?: continue
            if ((m.sender ?: m.author)?.let { it != self } != true) continue
            if (m.id in store || !allowlist.allowsConversation(m.conversationId)) continue
            todo[m.id] = fileId
        }
        if (todo.isEmpty()) return
        failed.clear()
        for (chunk in todo.entries.chunked(RECEIPT_BATCH)) {
            try {
                send(chunk.map { it.value })
                chunk.forEach { store.add(it.key) }
                log("read receipt sent for ${chunk.size} message(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("read receipt error: ${e.message}")
                chunk.forEach { failed[it.key] = it.value }
                while (failed.size > RECEIPT_RETRY_CAP) failed.remove(failed.keys.first())
            }
        }
    }

    private companion object {
        const val RECEIPT_BATCH = 100
        const val RECEIPT_RETRY_CAP = 200
    }
}

// > lastSeen plus ids at exactly lastSeen: no same-millisecond loss, no newest-message replay
class SeenCursor(lastSeen: Long) {
    var position = lastSeen
        private set
    private val atPosition = HashSet<Uuid>()

    fun fresh(msgs: List<ChatMsg>): List<ChatMsg> {
        val out = msgs.filter { it.userDate > position || (it.userDate == position && it.id !in atPosition) }.sortedBy { it.userDate }
        val newest = out.lastOrNull()?.userDate ?: return out
        if (newest > position) {
            position = newest
            atPosition.clear()
        }
        out.filter { it.userDate == position }.forEach { atPosition += it.id }
        return out
    }
}

class PollTimings(private val now: () -> Long = System::currentTimeMillis) {
    private val phases = LinkedHashMap<String, Long>()

    suspend fun <T> time(phase: String, block: suspend () -> T): T {
        val start = now()
        try {
            return block()
        } finally {
            phases.merge(phase, now() - start, Long::plus)
        }
    }

    fun slowLine(thresholdMs: Long = SLOW_POLL_MS): String? =
        if (phases.values.sum() > thresholdMs) "poll slow: " + phases.entries.joinToString(" ") { "${it.key}=${it.value}ms" } else null

    fun reset() = phases.clear()
}
