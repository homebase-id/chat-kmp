package id.homebase.agent

import id.homebase.api.util.truncateToCodePoints
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

const val BRAIN_TIMEOUT_MS = 120_000L
const val NO_REPLY = "NO_REPLY"
private const val PROCESSED_CAP = 1000
private const val POLL_INTERVAL_MS = 10_000L
private const val POLL_WINDOW = 50
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
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            return@withContext BrainOutcome.Failed("timeout after ${timeoutMs / 1000}s")
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

fun buildPrompt(trigger: ChatMsg, history: List<ChatMsg>): String = buildString {
    appendLine("Recent messages in this conversation (oldest first):")
    history.filter { it.id != trigger.id }.takeLast(HISTORY_LIMIT).forEach {
        appendLine("[${it.author}] ${it.text.truncateToCodePoints(MESSAGE_CODEPOINTS)}")
    }
    appendLine()
    appendLine("You were addressed by [${trigger.author}] with this message:")
    appendLine(trigger.text.truncateToCodePoints(MESSAGE_CODEPOINTS))
    appendLine()
    append("Reply concisely. If no reply is needed, output exactly $NO_REPLY.")
}

class WatchProcessor(
    private val config: AgentConfig,
    private val identity: String,
    private val store: ProcessedStore,
    private val history: suspend (Uuid) -> List<ChatMsg>,
    private val brain: suspend (String) -> BrainOutcome,
    private val reply: suspend (Uuid, String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val mutex = Mutex()
    private val seen = HashSet<Uuid>()

    suspend fun handle(msg: ChatMsg): String {
        if (msg.id in seen) return "seen"
        val result = decide(msg)
        seen.add(msg.id)
        log("${msg.id} $result")
        return result
    }

    private suspend fun decide(msg: ChatMsg): String {
        if (!config.allowlist.allowsConversation(msg.conversationId)) return "skip: conversation not allowed"
        if (!config.allowlist.allowsAuthor(msg.author, msg.conversationId)) return "skip: author not allowed"
        if (!shouldTrigger(msg.text, config.nickname, config.bot, identity)) return "skip: no trigger"
        if (msg.id in store) return "skip: already processed"
        if (!config.allowlist.allowsSend(msg.conversationId)) return "skip: send not permitted in this conversation"
        store.add(msg.id)
        return mutex.withLock {
            val prompt = buildPrompt(msg, history(msg.conversationId))
            val text = brainReply(brain(prompt)) ?: return@withLock "silent"
            reply(msg.conversationId, text)
            "replied"
        }
    }
}

suspend fun watch(profile: String) {
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
    val processor = WatchProcessor(
        config = config,
        identity = session.identity.toString(),
        store = store,
        history = { fetchMessages(session.credentials, it, HISTORY_LIMIT + 1) },
        brain = { runBrain(config.brain, it) },
        reply = { conversation, text ->
            sendToConversation(session, config.allowlist, conversation, text)
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
    try {
        while (true) {
            try {
                // ponytail: rediscover every poll; cache for N polls if the query ever hurts.
                refreshAllowlist(session.credentials, config.allowlist)
                val fresh = config.allowlist.allowedConversationIds()
                    .flatMap { fetchMessages(session.credentials, it, POLL_WINDOW) }
                    .filter { it.userDate >= lastSeen }
                    .sortedBy { it.userDate }
                for (msg in fresh) {
                    if (msg.userDate > lastSeen) {
                        lastSeen = msg.userDate
                        stateFile.writeText(lastSeen.toString())
                    }
                    processor.handle(msg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("poll error: ${e.message}")
            }
            delay(POLL_INTERVAL_MS)
        }
    } catch (e: CancellationException) {
        log("stopping")
    }
}
