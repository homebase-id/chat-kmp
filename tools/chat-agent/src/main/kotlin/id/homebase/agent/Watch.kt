package id.homebase.agent

import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

const val BRAIN_TIMEOUT_MS = 120_000L
const val NO_REPLY = "NO_REPLY"
private const val MAX_ATTEMPTS = 2
private const val PROCESSED_CAP = 1000
private const val POLL_INTERVAL_MS = 10_000L
private const val POLL_WINDOW = 50
private const val REDISCOVER_EVERY = 6
private const val HISTORY_LIMIT = 10
private const val MESSAGE_CODEPOINTS = 1000
private const val REPLY_CODEPOINTS = 1500

sealed interface BrainOutcome {
    class Output(val stdout: String) : BrainOutcome
    class Failed(val reason: String) : BrainOutcome
}

fun brainReply(outcome: BrainOutcome): String? = when (outcome) {
    is BrainOutcome.Failed -> "$BOT_PREFIX failed: ${outcome.reason.truncateToCodePoints(120)}"
    is BrainOutcome.Output -> outcome.stdout.trim().let {
        if (it.isEmpty() || it == NO_REPLY) null else "$BOT_PREFIX ${it.truncateToCodePoints(REPLY_CODEPOINTS)}"
    }
}

suspend fun runBrain(command: String, prompt: String, timeoutMs: Long = BRAIN_TIMEOUT_MS): BrainOutcome =
    withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("sh", "-c", command).start()
        } catch (e: Exception) {
            return@withContext BrainOutcome.Failed("could not start: ${e.message}")
        }
        val out = CompletableFuture.supplyAsync { process.inputStream.readBytes().decodeToString() }
        val err = CompletableFuture.supplyAsync { process.errorStream.readBytes().decodeToString() }
        Thread {
            runCatching { process.outputStream.use { it.write(prompt.encodeToByteArray()) } }
        }.apply { isDaemon = true }.start()
        try {
            if (!runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }) {
                return@withContext BrainOutcome.Failed("timeout after ${timeoutMs / 1000}s")
            }
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
        val code = process.exitValue()
        if (code != 0) {
            val detail = err.get(2, TimeUnit.SECONDS).trim().lineSequence().firstOrNull().orEmpty()
            return@withContext BrainOutcome.Failed("exit $code${if (detail.isNotEmpty()) ": $detail" else ""}")
        }
        BrainOutcome.Output(out.get(2, TimeUnit.SECONDS))
    }

class ProcessedStore(private val file: File?, private val cap: Int = PROCESSED_CAP) {
    private val ids = LinkedHashSet<String>()

    init {
        file?.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.takeLast(cap)?.let(ids::addAll)
    }

    operator fun contains(id: Uuid) = id.toString() in ids

    fun add(id: Uuid) {
        ids.add(id.toString())
        while (ids.size > cap) ids.remove(ids.first())
        file?.writeText(ids.joinToString("\n", postfix = "\n"))
    }

    val size get() = ids.size
}

fun buildPrompt(triggers: List<ChatMsg>, history: List<ChatMsg>, awayMode: Boolean = false): String = buildString {
    if (awayMode) {
        appendLine("The owner of this account is away. You are replying on their behalf as their AI assistant. Reply briefly, do not make commitments or promises for them, and if no reply is appropriate answer exactly $NO_REPLY.")
        appendLine()
    }
    val ids = triggers.map { it.id }.toSet()
    appendLine("Recent messages in this conversation (oldest first):")
    history.filter { it.id !in ids }.takeLast(HISTORY_LIMIT).forEach {
        appendLine("[${it.author}] ${it.text.truncateToCodePoints(MESSAGE_CODEPOINTS)}")
    }
    appendLine()
    appendLine("You were addressed in ${if (triggers.size == 1) "this message" else "these messages (oldest first)"}:")
    triggers.forEach { appendLine("[${it.author}] ${it.text.truncateToCodePoints(MESSAGE_CODEPOINTS)}") }
    appendLine()
    append("Reply concisely${if (triggers.size > 1) " with one reply covering all of them" else ""}. If no reply is needed, output exactly $NO_REPLY.")
}

class RunLimiter(
    private val file: File?,
    private val perHour: Int,
    private val perDay: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Run(val at: Long, val authors: Set<String>)

    private val runs = ArrayList<Run>()

    init {
        val cutoff = now() - DAY_MS
        file?.takeIf { it.exists() }?.readLines()?.forEach {
            val at = it.substringBefore('\t').toLongOrNull() ?: return@forEach
            if (at >= cutoff) runs += Run(at, it.substringAfter('\t', "").split(',').toSet())
        }
    }

    fun allows(authors: Set<String>): Boolean {
        val t = now()
        runs.removeAll { it.at < t - DAY_MS }
        if (runs.size >= perDay) return false
        return authors.all { a -> runs.count { it.at >= t - HOUR_MS && a in it.authors } < perHour }
    }

    fun record(authors: Set<String>) {
        runs += Run(now(), authors)
        file?.writeText(runs.joinToString("\n", postfix = "\n") { "${it.at}\t${it.authors.joinToString(",")}" })
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}

class WatchProcessor(
    private val config: AgentConfig,
    private val identity: String,
    private val store: ProcessedStore,
    private val history: suspend (Uuid) -> List<ChatMsg>,
    private val brain: suspend (String) -> BrainOutcome,
    private val reply: suspend (Uuid, String) -> Unit,
    private val log: (String) -> Unit,
    private val limiter: RunLimiter = RunLimiter(null, config.maxRunsPerHour, config.maxRunsPerDay),
    private val away: AwayFlag = AwayFlag(null),
) {
    private val mutex = Mutex()
    private val seen = HashSet<Uuid>()

    // ponytail: attempts and pending retries live in memory; a restart just retries from the processed file.
    private val attempts = HashMap<Uuid, Int>()
    private val pending = LinkedHashMap<Uuid, ChatMsg>()

    suspend fun handle(msg: ChatMsg): String = handleAll(listOf(msg))[msg.id] ?: "seen"

    suspend fun handleAll(msgs: List<ChatMsg>): Map<Uuid, String> {
        val results = LinkedHashMap<Uuid, String>()
        val eligible = ArrayList<ChatMsg>()
        for (msg in (pending.values + msgs).distinctBy { it.id }) {
            if (msg.id in seen && msg.id !in pending) { results[msg.id] = "seen"; continue }
            if (isAwayCommand(msg)) { toggleAway(msg, results); continue }
            val skip = precheck(msg)
            if (skip != null) { finish(msg.id, skip, results); continue }
            eligible += msg
        }
        for (group in eligible.groupBy { it.conversationId }.values) run(group, results)
        return results
    }

    private fun finish(id: Uuid, result: String, results: MutableMap<Uuid, String>) {
        seen.add(id)
        pending.remove(id)
        attempts.remove(id)
        results[id] = result
        log("$id $result")
    }

    private fun isOwn(msg: ChatMsg) = msg.author?.toString() == identity

    private fun isAwayCommand(msg: ChatMsg) =
        config.allowlist.delegate && msg.conversationId == ChatProtocol.ConversationWithYourselfId && isOwn(msg) &&
            msg.id !in store && awayCommand(msg.text, config.nickname) != null

    private suspend fun toggleAway(msg: ChatMsg, results: MutableMap<Uuid, String>) {
        val on = awayCommand(msg.text, config.nickname)!!
        away.on = on
        try {
            reply(msg.conversationId, "$BOT_PREFIX away ${if (on) "on" else "off"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("send error: ${e.message}")
        }
        store.add(msg.id)
        finish(msg.id, if (on) "away on" else "away off", results)
    }

    private fun precheck(msg: ChatMsg): String? {
        if (!config.allowlist.allowsConversation(msg.conversationId)) return "skip: conversation not allowed"
        if (config.allowlist.delegate && msg.conversationId != ChatProtocol.ConversationWithYourselfId && isOwn(msg)) return "skip: own message"
        if (!config.allowlist.allowsAuthor(msg.author, msg.conversationId)) return "skip: author not allowed"
        val awayMention = config.allowlist.delegate && away.on && msg.conversationId != ChatProtocol.ConversationWithYourselfId
        if (!shouldTrigger(msg.text, config.nickname, config.bot, identity, awayMention)) return "skip: no trigger"
        if (msg.id in store) return "skip: already processed"
        if (!config.allowlist.allowsSend(msg.conversationId)) return "skip: send not permitted in this conversation"
        return null
    }

    private suspend fun run(group: List<ChatMsg>, results: MutableMap<Uuid, String>) {
        val sorted = group.sortedBy { it.userDate }
        val conversation = sorted.first().conversationId
        val authors = sorted.map { it.author.toString() }.toSet()
        fun done(result: String) = sorted.forEach { store.add(it.id); finish(it.id, result, results) }
        mutex.withLock {
            if (!limiter.allows(authors)) return done("skip: rate limited")
            limiter.record(authors)
            val outcome = try {
                brain(buildPrompt(sorted, history(conversation), awayMode = away.on && config.allowlist.delegate && conversation != ChatProtocol.ConversationWithYourselfId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BrainOutcome.Failed(e.message ?: e.toString())
            }
            val text = brainReply(outcome)
                ?: return done("silent")
            val failed = outcome is BrainOutcome.Failed
            val attempt = (sorted.maxOf { attempts[it.id] ?: 0 }) + 1
            val settled = try {
                if (failed && attempt < MAX_ATTEMPTS) {
                    false
                } else {
                    reply(conversation, text)
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("send error: ${e.message}")
                attempt >= MAX_ATTEMPTS
            }
            if (!settled) {
                sorted.forEach { attempts[it.id] = attempt; pending[it.id] = it; results[it.id] = "retry" }
                log("${sorted.first().id} retry after attempt $attempt: ${(outcome as? BrainOutcome.Failed)?.reason ?: "send failed"}")
            } else {
                done(if (failed) "failed" else "replied")
            }
        }
    }
}

const val SLOW_POLL_MS = 5_000L

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

suspend fun watch(profile: String, verbose: Boolean = false) {
    val session = openSession(profile)
    val dir = Profile.dataDir(profile).also { it.mkdirs() }
    val logFile = File(dir, "logs/agent.log").also { it.parentFile.mkdirs() }
    fun log(line: String) {
        val entry = "${Instant.now()} ${line.replace('\n', ' ')}"
        println(entry)
        logFile.appendText(entry + "\n")
    }
    val config = loadConfig(profile, session.identity)
    val store = ProcessedStore(File(dir, "processed.txt"))
    val stateFile = File(dir, "last_seen.txt")
    var lastSeen = stateFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        ?: System.currentTimeMillis()
    val timings = PollTimings()
    val processor = WatchProcessor(
        config = config,
        identity = session.identity.toString(),
        store = store,
        limiter = RunLimiter(File(dir, "runs.txt"), config.maxRunsPerHour, config.maxRunsPerDay),
        away = AwayFlag(File(dir, "away")),
        history = { timings.time("history") { fetchMessages(session, it, HISTORY_LIMIT + 1) } },
        brain = { timings.time("brain") { runBrain(config.brain, it) } },
        reply = { conversation, text ->
            timings.time("send") { sendToConversation(session, config.allowlist, conversation, text) }
        },
        log = ::log,
    )

    val job = coroutineContext.job
    Runtime.getRuntime().addShutdownHook(Thread {
        job.cancel()
        runBlocking { job.join() }
        log("stopped")
    })
    log("watching as ${session.identity} nickname=${config.nickname} bot=${config.bot} transport=poll/${POLL_INTERVAL_MS}ms lastSeen=$lastSeen")
    var poll = 0
    try {
        while (true) {
            timings.reset()
            try {
                refreshAllowlist(session, config.allowlist, rediscover = poll++ % REDISCOVER_EVERY == 0, timings = timings)
                val fresh = timings.time("query") { fetchMessages(session, config.allowlist.allowedConversationIds().toList(), POLL_WINDOW) }
                    .filter { it.userDate >= lastSeen }
                    .sortedBy { it.userDate }
                if (verbose) log("poll: fresh=${fresh.size} maxUserDate=${fresh.maxOfOrNull { it.userDate }}")
                fresh.lastOrNull()?.takeIf { it.userDate > lastSeen }?.let {
                    lastSeen = it.userDate
                    stateFile.writeText(lastSeen.toString())
                }
                processor.handleAll(fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("poll error: ${e.message}")
            }
            timings.slowLine()?.let(::log)
            delay(POLL_INTERVAL_MS)
        }
    } catch (e: CancellationException) {
        log("stopping")
    }
}
