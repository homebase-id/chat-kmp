package id.homebase.agent

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

class JobRunner(
    private val scope: CoroutineScope,
    private val ledger: JobLedger,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val prefix: String = BOT_PREFIX,
    private val journal: File? = null,
) {
    private class Job(val id: Int, val conversation: Uuid, val operators: Set<String>, val work: suspend () -> BrainOutcome, val deliver: suspend (String) -> Unit) {
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
        operators: Set<String>,
        announce: suspend (String) -> Unit,
        deliver: suspend (String) -> Unit,
        work: suspend () -> BrainOutcome,
    ): String {
        val (job, ack) = synchronized(lock) {
            if (!operators.all(ledger::allows)) {
                null to tagged(prefix, "daily job limit reached")
            } else {
                operators.forEach(ledger::record)
                val job = Job(nextId++, conversation, operators, work, deliver)
                val ahead = queue.lastOrNull() ?: running
                queue.addLast(job)
                journal?.appendText("+\t${job.id}\t$conversation\n")
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

    fun canRun(operator: String) = ledger.allows(operator)

    fun busy(conversation: Uuid) = synchronized(lock) { running?.conversation == conversation || queue.any { it.conversation == conversation } }

    suspend fun refuse(operator: String, announce: suspend (String) -> Unit) {
        if (ledger.markNotified(operator)) announce(tagged(prefix, "your daily job limit (${ledger.perDay}) is reached"))
    }

    suspend fun recoverDropped(announce: suspend (Uuid, String) -> Unit) {
        val live = LinkedHashMap<String, String>()
        journal?.takeIf { it.exists() }?.readLines().orEmpty().forEach { line ->
            val parts = line.split('\t')
            when {
                parts.size == 3 && parts[0] == "+" -> live[parts[1]] = parts[2]
                parts.size == 2 && parts[0] == "-" -> live.remove(parts[1])
            }
        }
        journal?.delete()
        for ((id, conversation) in live) {
            val target = runCatching { Uuid.parse(conversation) }.getOrNull() ?: continue
            try {
                announce(target, tagged(prefix, "restarted: job $id was dropped, please send the request again"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("dropped-job notice error: ${e.message}")
            }
        }
    }

    private fun journalDrop(job: Job) {
        val f = journal ?: return
        if (running == null && queue.isEmpty()) f.delete() else f.appendText("-\t${job.id}\n")
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
                tagged(prefix, "job ${job.id} failed: ${failureLine(e.message ?: e.toString())}")
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
                synchronized(lock) { if (running === job) running = null; journalDrop(job); startNext() }
            }
        }
    }

    fun status(operator: String? = null): String = synchronized(lock) {
        val mine = operator?.let { "; your jobs today: ${ledger.used(it)}/${ledger.perDay}" }.orEmpty()
        val r = running ?: return tagged(prefix, "no jobs$mine")
        val minutes = (now() - r.startedAt) / 60_000
        val waiting = if (queue.isEmpty()) "" else "; queued: ${queue.joinToString(", ") { it.id.toString() }}"
        tagged(prefix, "job ${r.id} running for ${minutes}m$waiting$mine")
    }

    fun cancel(id: Int?, mayCancel: (Uuid) -> Boolean = { true }): String = synchronized(lock) {
        val target = id ?: running?.id ?: queue.firstOrNull()?.id ?: return tagged(prefix, "no jobs")
        val job = listOfNotNull(running).plus(queue).firstOrNull { it.id == target } ?: return tagged(prefix, "no such job $target")
        if (!mayCancel(job.conversation)) return tagged(prefix, "job $target was started in another conversation")
        if (running === job) {
            job.coroutine?.cancel()
        } else {
            queue.remove(job)
            journalDrop(job)
        }
        tagged(prefix, "cancelled job $target")
    }
}

class JobLedger(
    private val file: File?,
    val perDay: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val at: Long, val operator: String, val notice: Boolean)

    private val entries = ArrayList<Entry>()
    private var diskLines = 0

    init {
        val cutoff = now() - DAY_MS
        file?.takeIf { it.exists() }?.readLines()?.forEach {
            diskLines++
            val parts = it.split('\t')
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val op = parts.getOrNull(1)?.takeIf { s -> s.isNotEmpty() } ?: return@forEach
            if (at >= cutoff) entries += Entry(at, op, parts.getOrNull(2) == NOTICE)
        }
        if (diskLines > entries.size) compact()
    }

    private fun live(): List<Entry> {
        val cutoff = now() - DAY_MS
        entries.removeAll { it.at < cutoff }
        return entries
    }

    fun used(operator: String) = live().count { it.operator == operator && !it.notice }

    fun allows(operator: String) = used(operator) < perDay

    fun record(operator: String) = add(Entry(now(), operator, false))

    fun markNotified(operator: String): Boolean {
        if (live().any { it.operator == operator && it.notice }) return false
        add(Entry(now(), operator, true))
        return true
    }

    private fun add(e: Entry) {
        entries += e
        file?.appendText(line(e))
        if (++diskLines > 2 * entries.size + 100) compact()
    }

    private fun line(e: Entry) = "${e.at}\t${e.operator}${if (e.notice) "\t$NOTICE" else ""}\n"

    private fun compact() {
        file?.let { atomicWrite(it, entries.joinToString("") { e -> line(e) }) }
        diskLines = entries.size
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val NOTICE = "notice"
    }
}

fun jobText(id: Int, outcome: BrainOutcome, prefix: String = BOT_PREFIX): String = when (outcome) {
    is BrainOutcome.Failed -> tagged(prefix, "job $id failed: ${failureLine(outcome.reason)}")
    is BrainOutcome.Output -> sanitizeReply(outcome.stdout).let {
        if (it.isEmpty() || it == NO_REPLY) tagged(prefix, "job $id done (no output)") else tagged(prefix, capTextBytes(it))
    }
}

sealed interface OperatorCommand {
    data object Status : OperatorCommand
    data class Cancel(val id: Int?) : OperatorCommand
    data object New : OperatorCommand
    data object Schedules : OperatorCommand
    data class Unschedule(val id: String) : OperatorCommand
}

fun parseOperatorCommand(text: String, nickname: String): OperatorCommand? {
    val parts = text.trim().split(WHITESPACE)
    if (parts.size !in 2..3 || !parts[0].equals("@$nickname", ignoreCase = true)) return null
    val verb = parts[1].lowercase()
    if (parts.size == 3) {
        return when (verb) {
            "cancel" -> parts[2].toIntOrNull()?.let(OperatorCommand::Cancel)
            "unschedule" -> OperatorCommand.Unschedule(parts[2])
            else -> null
        }
    }
    return when (verb) {
        "status" -> OperatorCommand.Status
        "cancel" -> OperatorCommand.Cancel(null)
        "new" -> OperatorCommand.New
        "schedules" -> OperatorCommand.Schedules
        else -> null
    }
}
