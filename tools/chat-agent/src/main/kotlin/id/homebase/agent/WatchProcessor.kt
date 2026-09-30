package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_ATTEMPTS = 2

class WatchProcessor(
    private val config: AgentConfig,
    private val identity: String,
    private val store: ProcessedStore,
    private val history: suspend (Uuid) -> List<ChatMsg>,
    private val brain: suspend (String, Tier, List<Attachment>) -> BrainOutcome,
    private val send: suspend (Uuid, String, List<OutFile>) -> Unit,
    private val log: (String) -> Unit,
    private val limiter: RunLimiter = RunLimiter(null, config.maxRunsPerHour, config.maxRunsPerDay),
    private val away: AwayFlag = AwayFlag(null),
    private val jobs: JobRunner? = null,
    private val loader: AttachmentLoader? = null,
) {
    private val self = OdinId(identity)
    private val trust = TrustPolicy(config, self)
    private val allow = config.allowlist
    private val mutex = Mutex()
    private val seen = HashSet<Uuid>()

    // ponytail: attempts/pending live in memory; a restart retries from the processed file
    private val attempts = HashMap<Uuid, Int>()
    private val pending = LinkedHashMap<Uuid, ChatMsg>()

    private class Prepared(val prompt: String, val attachments: List<Attachment>)

    suspend fun handleAll(msgs: List<ChatMsg>): Map<Uuid, String> {
        val results = LinkedHashMap<Uuid, String>()
        val eligible = ArrayList<ChatMsg>()
        for (msg in (pending.values + msgs).distinctBy { it.id }) {
            if (msg.id in seen && msg.id !in pending) { results[msg.id] = "seen"; continue }
            if (isAwayCommand(msg)) { toggleAway(msg, results); continue }
            val skip = precheck(msg)
            if (skip != null) { finish(msg.id, skip, results); continue }
            val command = jobs?.let { jobCommand(msg.text, config.nickname) }
            if (command != null && trust.isOperator(msg.sender, msg.conversationId, allow.info(msg.conversationId)?.members)) {
                runJobCommand(jobs, msg, command, results)
                continue
            }
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

    private fun effectiveSender(msg: ChatMsg) = msg.sender ?: self

    private fun header(conversation: Uuid): String? {
        if (!config.described) return null
        val where = if (allow.isDirect(conversation)) "a private chat" else "a group conversation"
        return listOfNotNull(config.persona, "You are $identity, replying in $where. Its title and members are listed in the untrusted context block.").joinToString("\n")
    }

    private fun context(conversation: Uuid): String? {
        if (!config.described) return null
        val members = allow.info(conversation)?.members?.filter { it.toString() != identity }?.joinToString(", ").orEmpty()
        return "title: ${allow.title(conversation) ?: conversation}\nmembers: $members"
    }

    private suspend fun safeReply(conversation: Uuid, text: String, files: List<OutFile> = emptyList()): Boolean =
        try {
            send(conversation, text, files)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("send error: ${e.message}")
            false
        }

    private suspend fun runJobCommand(runner: JobRunner, msg: ChatMsg, command: Pair<String, Int?>, results: MutableMap<Uuid, String>) {
        val text = if (command.first == "status") runner.status() else runner.cancel(command.second) { jobConversation ->
            jobConversation == msg.conversationId ||
                (trust.isListed(msg.sender) && allow.info(jobConversation)?.members?.contains(effectiveSender(msg)) == true)
        }
        safeReply(msg.conversationId, text)
        store.add(msg.id)
        finish(msg.id, "job ${command.first}", results)
    }

    private suspend fun preparePrompt(sorted: List<ChatMsg>, conversation: Uuid, past: List<ChatMsg>, awayMode: Boolean): Prepared {
        val attachments = loadAttachments(sorted, past)
        return Prepared(buildPrompt(sorted, past, awayMode, header(conversation), context(conversation), attachments = attachments), attachments)
    }

    private suspend fun submitJob(
        runner: JobRunner,
        sorted: List<ChatMsg>,
        conversation: Uuid,
        authors: Set<String>,
        done: (String) -> Unit,
    ) {
        val prepared = try {
            preparePrompt(sorted, conversation, trust.history(history(conversation), conversation), awayMode = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("job history error: ${e.message}")
            return done("failed")
        }
        var produced = emptyList<OutFile>()
        runner.submit(
            conversation, authors,
            announce = { safeReply(conversation, it) },
            deliver = { safeReply(conversation, it, produced) },
            work = { brain(prepared.prompt, Tier.OPERATOR, prepared.attachments).also { produced = (it as? BrainOutcome.Output)?.files.orEmpty() } },
        )
        done("job")
    }

    private suspend fun loadAttachments(triggers: List<ChatMsg>, history: List<ChatMsg>): List<Attachment> {
        val loader = loader ?: return emptyList()
        val ids = triggers.map { it.id }.toSet()
        val targets = triggers.map { it to false } + triggers.mapNotNull { t ->
            replyParentId(t.rawContent)?.takeIf { it !in ids }?.let { pid -> history.firstOrNull { it.id == pid } }
        }.distinctBy { it.id }.map { it to true }
        return try {
            loader.load(targets)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("attachment error: ${e.message}")
            emptyList()
        }
    }

    private fun isOwn(msg: ChatMsg) = msg.author?.toString() == identity

    private fun isNoteToSelf(conversation: Uuid) = conversation == ChatProtocol.ConversationWithYourselfId

    private fun isAwayCommand(msg: ChatMsg) =
        allow.delegate && isNoteToSelf(msg.conversationId) && isOwn(msg) &&
            msg.id !in store && awayCommand(msg.text, config.nickname) != null

    private suspend fun toggleAway(msg: ChatMsg, results: MutableMap<Uuid, String>) {
        val on = awayCommand(msg.text, config.nickname)!!
        away.on = on
        safeReply(msg.conversationId, "$BOT_PREFIX away ${if (on) "on" else "off"}")
        store.add(msg.id)
        finish(msg.id, if (on) "away on" else "away off", results)
    }

    private fun precheck(msg: ChatMsg): String? {
        val conversation = msg.conversationId
        if (!allow.allowsConversation(conversation)) return "skip: conversation not allowed"
        if (isOwn(msg) && (config.bot || (allow.delegate && !isNoteToSelf(conversation)))) return "skip: own message"
        if (!allow.allowsAuthor(msg.author, conversation)) return "skip: author not allowed"
        val awayMention = allow.delegate && away.on && !isNoteToSelf(conversation)
        if (!shouldTrigger(msg.text, config.nick, config.bot, identity, awayMention, direct = allow.isDirect(conversation))) return "skip: no trigger"
        if (msg.id in store) return "skip: already processed"
        if (!allow.allowsSend(conversation)) return "skip: send not permitted in this conversation"
        return null
    }

    private suspend fun run(group: List<ChatMsg>, results: MutableMap<Uuid, String>) {
        val sorted = group.sortedBy { it.userDate }
        val conversation = sorted.first().conversationId
        val authors = sorted.map { it.author.toString() }.toSet()
        val tier = trust.tier(allow.info(conversation)?.members, isNoteToSelf(conversation), sorted.map(::effectiveSender).toSet(), conversation)
        fun done(result: String) = sorted.forEach { store.add(it.id); finish(it.id, result, results) }
        if (tier == Tier.OPERATOR && jobs != null) return submitJob(jobs, sorted, conversation, authors, ::done)
        mutex.withLock {
            if (!limiter.allows(authors)) return done("skip: rate limited")
            limiter.record(authors)
            val outcome = try {
                val fetched = history(conversation)
                val past = if (tier == Tier.OPERATOR) trust.history(fetched, conversation) else fetched
                val awayMode = away.on && allow.delegate && !isNoteToSelf(conversation)
                val prepared = preparePrompt(sorted, conversation, past, awayMode)
                brain(prepared.prompt, tier, prepared.attachments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BrainOutcome.Failed(e.message ?: e.toString())
            }
            val files = (outcome as? BrainOutcome.Output)?.files.orEmpty()
            val text = brainReply(outcome, config.replyPrefix)
                ?: if (files.isNotEmpty()) config.replyPrefix else return done("silent")
            val failed = outcome is BrainOutcome.Failed
            val attempt = (sorted.maxOf { attempts[it.id] ?: 0 }) + 1
            val settled = if (failed && attempt < MAX_ATTEMPTS) false else safeReply(conversation, text, files) || attempt >= MAX_ATTEMPTS
            if (!settled) {
                sorted.forEach { attempts[it.id] = attempt; pending[it.id] = it; results[it.id] = "retry" }
                log("${sorted.first().id} retry after attempt $attempt: ${(outcome as? BrainOutcome.Failed)?.reason ?: "send failed"}")
            } else {
                done(if (failed) "failed" else "replied")
            }
        }
    }
}
