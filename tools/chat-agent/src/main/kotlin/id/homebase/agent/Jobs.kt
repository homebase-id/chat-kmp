package id.homebase.agent

import id.homebase.api.util.truncateToCodePoints
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class JobRunner(
    private val scope: CoroutineScope,
    private val limiter: RunLimiter,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val prefix: String = BOT_PREFIX,
) {
    private class Job(val id: Int, val authors: Set<String>, val work: suspend () -> BrainOutcome, val deliver: suspend (String) -> Unit) {
        var ready = false
        var startedAt = 0L
        var coroutine: kotlinx.coroutines.Job? = null
    }

    private val lock = Any()
    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    private var nextId = 1

    suspend fun submit(
        conversation: Uuid,
        authors: Set<String>,
        announce: suspend (String) -> Unit,
        deliver: suspend (String) -> Unit,
        work: suspend () -> BrainOutcome,
    ): String {
        val (job, ack) = synchronized(lock) {
            if (!limiter.allows(authors)) {
                null to tagged(prefix, "daily job limit reached")
            } else {
                limiter.record(authors)
                val job = Job(nextId++, authors, work, deliver)
                val ahead = queue.lastOrNull() ?: running
                queue.addLast(job)
                job to (if (ahead == null) tagged(prefix, "on it (job ${job.id})") else tagged(prefix, "queued behind job ${ahead.id} (job ${job.id})"))
            }
        }
        try {
            announce(ack)
        } finally {
            if (job != null) synchronized(lock) { job.ready = true; startNext() }
        }
        if (job != null) log("$conversation job ${job.id} accepted")
        return ack
    }

    private fun startNext() {
        if (running != null) return
        val job = queue.firstOrNull()?.takeIf { it.ready } ?: return
        queue.removeFirst()
        running = job
        job.startedAt = now()
        job.coroutine = scope.launch {
            val text = try {
                jobText(job.id, job.work(), prefix)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tagged(prefix, "job ${job.id} failed: ${e.message}")
            }
            try {
                job.deliver(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("job ${job.id} send error: ${e.message}")
            }
        }.also {
            it.invokeOnCompletion {
                synchronized(lock) { if (running === job) running = null; startNext() }
            }
        }
    }

    fun status(): String = synchronized(lock) {
        val r = running ?: return tagged(prefix, "no jobs")
        val minutes = (now() - r.startedAt) / 60_000
        val waiting = if (queue.isEmpty()) "" else "; queued: ${queue.joinToString(", ") { it.id.toString() }}"
        tagged(prefix, "job ${r.id} running for ${minutes}m$waiting")
    }

    fun cancel(id: Int?): String = synchronized(lock) {
        val target = id ?: running?.id ?: queue.firstOrNull()?.id ?: return tagged(prefix, "no jobs")
        val r = running
        if (r != null && r.id == target) {
            r.coroutine?.cancel()
            return tagged(prefix, "cancelled job $target")
        }
        if (queue.removeAll { it.id == target }) tagged(prefix, "cancelled job $target") else tagged(prefix, "no such job $target")
    }
}

fun tailTruncate(text: String, maxCodePoints: Int): String {
    if (text.codePointCount(0, text.length) <= maxCodePoints) return text
    val start = text.offsetByCodePoints(text.length, -(maxCodePoints - 1))
    return "…" + text.substring(start)
}

fun jobText(id: Int, outcome: BrainOutcome, prefix: String = BOT_PREFIX): String = when (outcome) {
    is BrainOutcome.Failed -> tagged(prefix, "job $id failed: ${outcome.reason.truncateToCodePoints(120)}")
    is BrainOutcome.Output -> sanitizeReply(outcome.stdout).let {
        if (it.isEmpty() || it == NO_REPLY) tagged(prefix, "job $id done (no output)") else tagged(prefix, tailTruncate(it, REPLY_CODEPOINTS))
    }
}

fun jobCommand(text: String, nickname: String): Pair<String, Int?>? {
    val parts = text.trim().split(Regex("\\s+"))
    if (parts.size !in 2..3 || !parts[0].equals("@$nickname", ignoreCase = true)) return null
    val verb = parts[1].lowercase()
    if (verb != "status" && verb != "cancel") return null
    if (parts.size == 2) return verb to null
    if (verb == "status") return null
    return parts[2].toIntOrNull()?.let { verb to it }
}
