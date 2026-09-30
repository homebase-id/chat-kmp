package id.homebase.agent

import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.SendReadReceiptResult
import id.homebase.api.client.drives.files.SendReadReceiptResultStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.services.XorIdUtil
import java.io.File
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val POLL_WINDOW = 50
private const val REDISCOVER_EVERY = 6

fun deriveDirectChats(self: OdinId, msgs: List<ChatMsg>, allowlist: Allowlist): List<ChatMsg> {
    val already = allowlist.allowedConversationIds()
    return msgs.filter { msg ->
        val peer = (msg.sender ?: msg.author)?.takeIf { it != self } ?: return@filter false
        if (msg.conversationId in already) return@filter false
        if (!XorIdUtil.isOneToOneWithSender(self, peer, msg.conversationId)) return@filter false
        allowlist.learnDerived(ConversationInfo(msg.conversationId, peer.toString(), listOf(self, peer)))
        true
    }
}

suspend fun pollMessages(session: Session, allowlist: Allowlist): List<ChatMsg> {
    if (!allowlist.memberMode) return fetchMessages(session, allowlist.allowedConversationIds().toList(), POLL_WINDOW)
    val all = fetchMessages(session, null, POLL_WINDOW)
    val allowed = allowlist.allowedConversationIds()
    return all.filter { it.conversationId in allowed } + deriveDirectChats(session.identity, all, allowlist)
}

private fun logReceiptStatuses(log: (String) -> Unit, result: SendReadReceiptResult) {
    result.results.flatMap { it.status }.filter { it.status != SendReadReceiptResultStatus.Enqueued }
        .groupingBy { it.status }.eachCount()
        .forEach { (status, n) -> log("read receipt not enqueued: $status x$n") }
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
    val stateFile = File(dir, "last_seen.txt")
    val cursor = SeenCursor(stateFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: System.currentTimeMillis())
    val timings = PollTimings()
    val previews = config.previewsFor(session)
    val jobs = config.operatorBrain?.let {
        JobRunner(CoroutineScope(SupervisorJob(coroutineContext.job)), JobLedger(File(dir, "jobs.txt"), config.maxJobsPerDay), ::log, prefix = config.replyPrefix, journal = File(dir, "jobs-pending.txt"))
    }
    val fetcher = sessionFetcher(session)
    val toolServer = if (config.operatorBrain != null || config.lockedChat) ChatToolServer(::log).start() else null
    val chatTools = toolServer?.let { ChatTools(it, config, session, previews) }
    val processor = WatchProcessor(
        config = config,
        identity = session.identity.toString(),
        store = ProcessedStore(File(dir, "processed.txt")),
        limiter = RunLimiter(File(dir, "runs.txt"), config.maxRunsPerHour, config.maxRunsPerDay),
        away = AwayFlag(File(dir, "away")),
        jobs = jobs,
        history = { timings.time("history") { fetchMessages(session, it, maxOf(HISTORY_LIMIT, config.lockedHistory) + 1) } },
        loader = AttachmentLoader(fetcher, config.transcribe?.let(::shellTranscriber), ::log),
        fetcher = fetcher,
        leaseFor = chatTools?.let { tools -> { conversation, tier, sender -> tools.lease(conversation, tier, sender) } },
        brain = { prompt, tier, attachments, lease ->
            timings.time("brain") {
                if (tier == Tier.OPERATOR) runBrain(Brain(config.operatorBrain!!), prompt, config.operatorTimeoutMs, tier = tier, operatorCwd = config.operatorCwd, attachments = attachments, lease = lease, mcpGroup = config.operatorGroup)
                else runBrain(config.brain, prompt, attachments = attachments, lease = lease)
            }
        },
        send = { conversation, text, files ->
            timings.time("send") { sendToConversation(session, config.allowlist, conversation, text, files = files, previews = previews) }
        },
        log = ::log,
    )

    val receipts = ReadReceipts(
        enabled = config.sendsReceipts,
        self = session.identity,
        allowlist = config.allowlist,
        store = ProcessedStore(File(dir, "receipts.txt")),
        send = { ids -> logReceiptStatuses(::log, session.driveFiles.sendReadReceiptBatch(SystemDriveConstants.chatDrive.alias, ids)) },
        log = ::log,
    )

    val job = coroutineContext.job
    Runtime.getRuntime().addShutdownHook(Thread {
        job.cancel()
        runBlocking { job.join() }
        log("stopped")
        Runtime.getRuntime().halt(0)
    })
    runCatching { refreshAllowlist(session, config.allowlist) }.onFailure { log("startup discovery error: ${it.message}") }
    (config.warnings + tierBanner(config)).forEach(::log)
    config.operatorGroup?.let { g ->
        runCatching { java.nio.file.FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByGroupName(g) }
            .onFailure { log("WARNING: operatorGroup '$g' does not exist; operator MCP token files stay private (700/600), a brain running as another OS user cannot read them") }
    }
    toolServer?.let { log("chat tools on 127.0.0.1:${it.port} (per-run bearer token, loopback only)") }
    jobs?.recoverDropped { conversation, text -> sendToConversation(session, config.allowlist, conversation, text) }
    log("watching as ${session.identity} nickname=${config.nickname} bot=${config.bot} readReceipts=${config.sendsReceipts} transport=${config.transport.name.lowercase()} lastSeen=${cursor.position}")
    var poll = 0
    val waker = PollWaker()
    try {
      coroutineScope {
        startDoorbell(this, config.transport, sessionConnector(session, verbose, ::log), waker, ::log)
        while (true) {
            timings.reset()
            Profile.harden(dir)
            try {
                refreshAllowlist(session, config.allowlist, rediscover = poll++ % REDISCOVER_EVERY == 0, timings = timings)
                val before = cursor.position
                val fresh = cursor.fresh(timings.time("query") { pollMessages(session, config.allowlist) })
                if (verbose) log("poll: fresh=${fresh.size} maxUserDate=${fresh.maxOfOrNull { it.userDate }}")
                if (cursor.position != before) stateFile.writeText(cursor.position.toString())
                coroutineScope {
                    launch { timings.time("receipt") { receipts.mark(fresh) } }
                    processor.handleAll(fresh)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("poll error: ${e.message}")
            }
            timings.slowLine()?.let(::log)
            waker.await()
        }
      }
    } catch (e: CancellationException) {
        log("stopping")
    } finally {
        toolServer?.close()
    }
}
