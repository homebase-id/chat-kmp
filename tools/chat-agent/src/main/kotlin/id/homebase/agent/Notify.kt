package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val NOTIFY_TOOL = "notify_owner"
const val NOTIFY_TEXT_CODEPOINTS = 1500
const val MAX_NOTIFY_PER_HOUR = 6
const val DEFAULT_OWNER_CALL_AFTER_MS = 10 * 60_000L
const val DEFAULT_MAX_OWNER_CALLS_PER_DAY = 3
const val DEFAULT_NOTIFY_JOBS_AFTER_MS = 2 * 60_000L
const val NO_OWNER_CONVERSATION = "owner has no 1:1 with the bot"
private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L
private const val RESTART_GRACE_MS = 60_000L
private const val REMEMBERED = 50
private const val SUMMARY_CODEPOINTS = 200

enum class Urgency {
    INFO, ATTENTION, URGENT;

    companion object {
        fun parse(text: String?): Urgency =
            if (text == null) INFO else entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("urgency must be info, attention or urgent")
    }
}

enum class CallOutcome { ANSWERED, MISSED, DECLINED, UNAVAILABLE, FAILED }

interface OwnerCaller {
    suspend fun call(peer: OdinId, say: String): CallOutcome

    object None : OwnerCaller {
        override suspend fun call(peer: OdinId, say: String) = CallOutcome.UNAVAILABLE
    }
}

class QuietHours(val start: LocalTime, val end: LocalTime) {
    fun contains(ms: Long, zone: ZoneId): Boolean {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        return if (start < end) t >= start && t < end else t >= start || t < end
    }
}

private val QUIET_FORM = Regex("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})")

fun parseQuietHours(text: String): QuietHours? {
    val m = QUIET_FORM.matchEntire(text.trim()) ?: return null
    return runCatching {
        val (a, b, c, d) = m.groupValues.drop(1).map { it.toInt() }
        QuietHours(LocalTime.of(a, b), LocalTime.of(c, d)).takeIf { it.start != it.end }
    }.getOrNull()
}

class NotifyPolicy(
    val callAfterMs: Long = DEFAULT_OWNER_CALL_AFTER_MS,
    val maxCallsPerDay: Int = DEFAULT_MAX_OWNER_CALLS_PER_DAY,
    val quiet: QuietHours? = null,
    val urgentOverridesQuiet: Boolean = true,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun isQuiet(ms: Long) = quiet?.contains(ms, zone) == true
}

class CallBudget(private val file: File?, val perDay: Int, private val now: () -> Long = System::currentTimeMillis) {
    private val times = ArrayList<Long>()

    init {
        val cutoff = now() - DAY_MS
        val lines = file?.takeIf { it.exists() }?.readLines().orEmpty()
        lines.mapNotNullTo(times) { it.trim().toLongOrNull()?.takeIf { at -> at >= cutoff } }
        if (file != null && lines.size > times.size) atomicWrite(file, times.joinToString("") { "$it\n" })
    }

    @Synchronized fun allows(): Boolean {
        times.removeAll { it < now() - DAY_MS }
        return times.size < perDay
    }

    @Synchronized fun record() {
        val at = now()
        times += at
        file?.appendText("$at\n")
    }
}

class Escalation(val id: String, val messageId: Uuid, val sentAt: Long, val text: String, val source: String)

class NotifyResult(val messageId: Uuid, val conversation: Uuid)

class OwnerNotifier(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    val owner: OdinId,
    private val conversation: () -> Uuid?,
    private val policy: NotifyPolicy,
    private val send: suspend (Uuid, String) -> Uuid,
    private val speak: (suspend (String) -> OutVoice)? = null,
    private val sendVoice: (suspend (Uuid, OutVoice, String) -> Uuid)? = null,
    private val caller: OwnerCaller = OwnerCaller.None,
    private val store: File? = null,
    private val calls: CallBudget = CallBudget(null, policy.maxCallsPerDay, clock),
    private val prefix: String = "",
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, Escalation>()
    private val timers = HashMap<String, Job>()
    private val toolCalls = ArrayDeque<Long>()
    private val callGate = Mutex()
    private val recent = bounded<Uuid, String>()
    private val replying = bounded<Uuid, String>()

    private fun <K, V> bounded() = object : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > REMEMBERED
    }

    fun start() {
        val now = clock()
        val loaded = if (caller === OwnerCaller.None) emptyList() else load().filter { now - it.sentAt < DAY_MS }
        synchronized(lock) {
            loaded.forEach { pending[it.source] = it; recent[it.messageId] = it.text }
            save()
        }
        loaded.forEach { e -> schedule(e, (e.sentAt + policy.callAfterMs).takeIf { it > now } ?: (now + RESTART_GRACE_MS)) }
        if (loaded.isNotEmpty()) log("owner escalations: ${loaded.size} pending after restart")
    }

    fun ownerConversation(): Uuid? = conversation()

    fun target(): Uuid = conversation() ?: run {
        log(NO_OWNER_CONVERSATION)
        throw IllegalArgumentException("the owner has no 1:1 conversation with the bot")
    }

    fun pendingCount() = synchronized(lock) { pending.size }

    fun allowToolCall(): Boolean = synchronized(lock) {
        val now = clock()
        while (toolCalls.isNotEmpty() && toolCalls.first() <= now - HOUR_MS) toolCalls.removeFirst()
        (toolCalls.size < MAX_NOTIFY_PER_HOUR).also { if (it) toolCalls.addLast(now) }
    }

    suspend fun notify(text: String, urgency: Urgency, source: String): NotifyResult {
        val target = target()
        val id = send(target, tagged(prefix, text))
        val sentAt = clock()
        synchronized(lock) { recent[id] = text }
        log("owner notified (${urgency.name.lowercase()}) from $source")
        if (urgency == Urgency.URGENT && caller !== OwnerCaller.None) escalate(Escalation(newToken(4), id, sentAt, text, source))
        if (urgency != Urgency.INFO) voiceNote(target, text, urgency, sentAt)
        return NotifyResult(id, target)
    }

    private fun voiceNote(target: Uuid, text: String, urgency: Urgency, at: Long) {
        val speak = speak ?: return
        val sendVoice = sendVoice ?: return
        if (policy.isQuiet(at) && !(urgency == Urgency.URGENT && policy.urgentOverridesQuiet)) return
        scope.launch {
            try {
                sendVoice(target, speak(text), "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("owner voice note failed: ${e.message}")
            }
        }
    }

    private fun escalate(e: Escalation) {
        synchronized(lock) {
            timers.remove(e.source)?.cancel()
            pending[e.source] = e
            save()
        }
        schedule(e, e.sentAt + policy.callAfterMs)
    }

    private fun schedule(e: Escalation, at: Long) {
        val timer = scope.launch {
            delay((at - clock()).coerceAtLeast(0))
            check(e)
        }
        synchronized(lock) { timers[e.source] = timer }
    }

    private fun drop(e: Escalation, cancelTimer: Boolean) = synchronized(lock) {
        if (pending[e.source]?.id == e.id) {
            pending.remove(e.source)
            timers.remove(e.source)?.let { if (cancelTimer) it.cancel() }
            save()
        }
    }

    private suspend fun check(e: Escalation) = callGate.withLock {
        if (synchronized(lock) { pending[e.source]?.id } != e.id) return@withLock
        val now = clock()
        when {
            policy.isQuiet(now) && !policy.urgentOverridesQuiet -> {
                drop(e, false)
                log("owner call skipped: quiet hours")
            }
            !calls.allows() -> {
                drop(e, false)
                log("owner call skipped: daily call limit (${calls.perDay}) reached")
            }
            else -> {
                drop(e, false)
                val outcome = try {
                    caller.call(owner, e.text)
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    log("owner call error: ${x.message}")
                    CallOutcome.FAILED
                }
                if (outcome != CallOutcome.UNAVAILABLE) calls.record()
                log("owner call ${outcome.name.lowercase()}")
                followUp(outcome, e.text)
            }
        }
    }

    private suspend fun followUp(outcome: CallOutcome, text: String) {
        val target = conversation() ?: return
        val what = when (outcome) {
            CallOutcome.ANSWERED -> "called you (answered)"
            CallOutcome.MISSED -> "tried to call you (missed)"
            CallOutcome.DECLINED -> "tried to call you (declined)"
            CallOutcome.UNAVAILABLE -> "could not call you (calling is not available)"
            CallOutcome.FAILED -> "tried to call you (the call failed)"
        }
        try {
            send(target, tagged(prefix, "$what about: ${text.oneLine(80)}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("owner call follow-up error: ${e.message}")
        }
    }

    fun ownerSpoke(target: Uuid, at: Long) {
        if (target != conversation()) return
        val answered = synchronized(lock) { pending.values.filter { it.sentAt <= at } }
        answered.forEach { drop(it, true) }
        if (answered.isNotEmpty()) log("owner replied, ${answered.size} escalation(s) cancelled")
    }

    fun observe(msg: ChatMsg) {
        if (msg.sender != owner) return
        val target = conversation() ?: return
        if (msg.conversationId != target) return
        replyParentId(msg.rawContent)?.let { parent ->
            val text = answered(parent)
            if (text != null) synchronized(lock) { replying[msg.id] = text }
        }
        ownerSpoke(target, clock())
    }

    private fun answered(parent: Uuid): String? {
        val hit = synchronized(lock) { pending.values.firstOrNull { it.messageId == parent } }
        hit?.let { drop(it, true) }
        return synchronized(lock) { recent[parent] }
    }

    fun replyingTo(message: Uuid): String? = synchronized(lock) { replying[message] }

    private fun save() {
        val f = store ?: return
        atomicWrite(f, JsonArray(pending.values.map {
            buildJsonObject {
                put("id", it.id); put("messageId", it.messageId.toString()); put("sentAt", it.sentAt); put("text", it.text); put("source", it.source)
            }
        }).toString())
    }

    private fun load(): List<Escalation> {
        val f = store?.takeIf { it.exists() } ?: return emptyList()
        return try {
            Json.parseToJsonElement(f.readText()).jsonArray.map {
                val o = it.jsonObject
                fun s(k: String) = o.getValue(k).jsonPrimitive.content
                Escalation(s("id"), Uuid.parse(s("messageId")), o.getValue("sentAt").jsonPrimitive.long, s("text"), s("source"))
            }
        } catch (e: Exception) {
            log("WARNING: ${f.name} was unreadable, starting with no escalations: ${e.message}")
            emptyList()
        }
    }
}

fun jobNotice(report: JobReport, title: String, ownerConversation: Uuid?, afterMs: Long): Pair<Urgency, String>? {
    val failed = report.status == JobStatus.FAILED
    val elsewhere = report.conversation != ownerConversation
    val worth = (elsewhere && (failed || report.durationMs >= afterMs)) || (report.schedule != null && failed)
    if (!worth) return null
    val label = if (report.schedule != null) "scheduled task ${report.schedule} (job ${report.id})" else "job ${report.id}"
    val tail = report.firstLine.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
    return when (report.status) {
        JobStatus.DONE -> Urgency.INFO to "$label done in $title$tail"
        JobStatus.FAILED -> Urgency.ATTENTION to "$label failed in $title$tail"
        JobStatus.CANCELLED -> Urgency.INFO to "$label cancelled in $title"
    }
}

fun jobSummary(outcome: BrainOutcome): String = when (outcome) {
    is BrainOutcome.Failed -> failureLine(outcome.reason)
    is BrainOutcome.Output -> classifyReply(outcome.stdout).let { r ->
        if (r.kind == ReplyKind.SILENT) "" else r.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().oneLine(SUMMARY_CODEPOINTS)
    }
}

private suspend fun sendAttachment(backend: AgentBackend, conversation: Uuid, file: OutFile) {
    val ext = file.name.substringAfterLast('.', "").lowercase()
    VOICE_TYPES[ext]?.let {
        backend.sendVoice(conversation, OutVoice(file.name, file.bytes, it, voiceSeconds(backend.ffmpeg, file) ?: 0), "")
        return
    }
    if (ext == "mp4" && backend.ffmpeg.available && file.bytes.size <= VIDEO_MAX_BYTES) {
        val video = try {
            probeOutVideo(backend.ffmpeg, file)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (video != null) {
            backend.sendVideo(conversation, video, "")
            return
        }
    }
    backend.sendFile(conversation, file, "")
}

suspend fun toolNotifyOwner(backend: AgentBackend, args: kotlinx.serialization.json.JsonObject?): ToolReply = guarded {
    val notifier = backend.notifier ?: return@guarded ToolReply("owner notifications are not configured", true)
    val text = stringArg(args, "text") ?: return@guarded ToolReply("text is empty", true)
    require(text.codePointCount(0, text.length) <= NOTIFY_TEXT_CODEPOINTS) { "text is longer than $NOTIFY_TEXT_CODEPOINTS characters" }
    val urgency = Urgency.parse(stringArg(args, "urgency"))
    notifier.target()
    val file = stringArg(args, "path")?.let { fileArg(backend, args) }
    require(notifier.allowToolCall()) { "notify_owner is limited to $MAX_NOTIFY_PER_HOUR calls per hour" }
    val source = "chat:${backend.conversation?.toString()?.take(ID_PREFIX) ?: "brain"}"
    val r = notifier.notify(text, urgency, source)
    val warning = file?.let {
        try {
            sendAttachment(backend.toOwner(), r.conversation, it)
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "; text sent, but the attachment failed: ${e.message}"
        }
    }.orEmpty()
    ToolReply("notified owner (${urgency.name.lowercase()}), message ${r.messageId.toString().take(ID_PREFIX)}$warning")
}

private fun prop(type: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
}

fun notifyTools(): List<ToolDef> = listOf(
    ToolDef(
        NOTIFY_TOOL,
        "Message the owner in their own private chat with this bot, whatever conversation you are working in. urgency info sends a text; attention adds a voice note when voice is set up; urgent also escalates to a phone call if they do not reply. Optional path attaches any file, image, video or audio from the files directory. At most $MAX_NOTIFY_PER_HOUR per hour.",
        buildJsonObject {
            put("text", prop("string", "What the owner needs to know or decide, at most $NOTIFY_TEXT_CODEPOINTS characters"))
            put("urgency", prop("string", "info (default), attention or urgent"))
            put("path", prop("string", "Optional file inside the files directory to send along"))
        },
        listOf("text"), stdio = false, write = true,
    ) { b, a -> toolNotifyOwner(b, a) },
)
