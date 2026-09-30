package id.homebase.agent

import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val MAX_SCHEDULES_PER_CONVERSATION = 10
const val MAX_SCHEDULE_PROMPT_CODEPOINTS = 2000
const val MIN_SCHEDULE_INTERVAL_MINUTES = 15L
const val SCHEDULE_TICK_MS = 30_000L
const val SCHEDULE_FORMS = "every <N>m|h (at least 15 minutes), daily HH:MM, every weekday HH:MM, every mon|tue|wed|thu|fri|sat|sun[,<day>...] HH:MM, once YYYY-MM-DD HH:MM"

private val DAYS = DayOfWeek.entries.associateBy { it.name.take(3).lowercase() }
private val EVERY_INTERVAL = Regex("every (\\d{1,6}[mh])")
private val COMMA_SPACES = Regex("\\s*,\\s*")
private val AT_TIME = Regex("(\\d{1,2}):(\\d{2})")
private val SCHEDULE_ID = Regex("[0-9a-f]{6}")
private val NEXT_RUN_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

sealed interface When {
    fun next(after: ZonedDateTime): ZonedDateTime?

    class Interval(val minutes: Long) : When {
        override fun next(after: ZonedDateTime) = after.plusMinutes(minutes)
    }

    class Daily(val days: Set<DayOfWeek>, val time: LocalTime) : When {
        override fun next(after: ZonedDateTime): ZonedDateTime? =
            (0L..7L).asSequence().map { after.toLocalDate().plusDays(it) }
                .filter { it.dayOfWeek in days }
                .map { ZonedDateTime.of(it, time, after.zone) }
                .first { it.isAfter(after) }
    }

    class Once(val at: LocalDateTime) : When {
        override fun next(after: ZonedDateTime) = ZonedDateTime.of(at, after.zone).takeIf { it.isAfter(after) }
    }
}

private fun parseTime(text: String): LocalTime {
    val m = AT_TIME.matchEntire(text) ?: throw IllegalArgumentException("bad time '$text'; forms: $SCHEDULE_FORMS")
    return runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        .getOrElse { throw IllegalArgumentException("bad time '$text'; forms: $SCHEDULE_FORMS") }
}

fun canonicalWhen(text: String) = text.trim().lowercase().replace(COMMA_SPACES, ",").replace(WHITESPACE, " ")

fun parseWhen(text: String, zone: ZoneId, now: ZonedDateTime): When {
    val w = canonicalWhen(text)
    val bad = IllegalArgumentException("unrecognised schedule '$text'; forms: $SCHEDULE_FORMS")
    EVERY_INTERVAL.matchEntire(w)?.let {
        val minutes = (parseDurationMs(it.groupValues[1]) ?: 0L) / 60_000
        require(minutes >= MIN_SCHEDULE_INTERVAL_MINUTES) { "interval must be at least $MIN_SCHEDULE_INTERVAL_MINUTES minutes" }
        return When.Interval(minutes)
    }
    val parts = w.split(' ')
    return when {
        parts.size == 2 && parts[0] == "daily" -> When.Daily(DayOfWeek.entries.toSet(), parseTime(parts[1]))
        parts.size == 3 && parts[0] == "every" && parts[1] == "weekday" ->
            When.Daily(DayOfWeek.entries.filter { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }.toSet(), parseTime(parts[2]))
        parts.size == 3 && parts[0] == "every" && parts[1].split(',').all { it in DAYS } ->
            When.Daily(parts[1].split(',').map { DAYS.getValue(it) }.toSet(), parseTime(parts[2]))
        parts.size == 3 && parts[0] == "once" -> {
            val date = runCatching { LocalDate.parse(parts[1]) }.getOrElse { throw bad }
            val at = LocalDateTime.of(date, parseTime(parts[2]))
            require(ZonedDateTime.of(at, zone).isAfter(now)) { "once-time must be in the future" }
            When.Once(at)
        }
        else -> throw bad
    }
}

class Schedule(
    val id: String,
    val conversation: String,
    val creator: String,
    val whenText: String,
    val prompt: String,
    val created: Long,
    val lastRun: Long?,
    val nextRun: Long,
    val enabled: Boolean = true,
) {
    fun copy(lastRun: Long? = this.lastRun, nextRun: Long = this.nextRun, enabled: Boolean = this.enabled) =
        Schedule(id, conversation, creator, whenText, prompt, created, lastRun, nextRun, enabled)
}

class ScheduleStore(
    private val file: File?,
    val zone: ZoneId,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val items = ArrayList<Schedule>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            var bad = false
            try {
                Json.parseToJsonElement(f.readText()).jsonArray.forEach { e ->
                    try {
                        val o = e.jsonObject
                        fun s(k: String) = o.getValue(k).jsonPrimitive.content
                        items += Schedule(
                            s("id").also { require(SCHEDULE_ID.matches(it)) }, Uuid.parse(s("conversation")).toString(), s("creator"), s("when"), s("prompt"),
                            o.getValue("created").jsonPrimitive.long, o["lastRun"]?.jsonPrimitive?.longOrNull, o.getValue("nextRun").jsonPrimitive.long,
                            o["enabled"]?.jsonPrimitive?.boolean ?: true,
                        )
                    } catch (_: Exception) {
                        bad = true
                    }
                }
            } catch (_: Exception) {
                bad = true
            }
            if (bad) {
                val kept = File(f.parentFile, "${f.name}.corrupt-${now()}")
                f.renameTo(kept)
                log("WARNING: ${f.name} was unreadable or had invalid entries; moved to ${kept.name}, starting with ${items.size} valid schedules")
            }
        }
    }

    private fun zoned(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone)

    private fun save() {
        file?.let { f -> atomicWrite(f, JsonArray(items.map { s ->
            buildJsonObject {
                put("id", s.id); put("conversation", s.conversation); put("creator", s.creator); put("when", s.whenText); put("prompt", s.prompt)
                put("created", s.created); put("lastRun", s.lastRun?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull); put("nextRun", s.nextRun); put("enabled", s.enabled)
            }
        }).toString()) }
    }

    @Synchronized fun create(conversation: Uuid, creator: String, whenText: String, prompt: String): Schedule {
        val text = prompt.trim()
        require(text.isNotEmpty()) { "prompt is empty" }
        require(text.codePointCount(0, text.length) <= MAX_SCHEDULE_PROMPT_CODEPOINTS) { "prompt is longer than $MAX_SCHEDULE_PROMPT_CODEPOINTS characters" }
        require(items.count { it.conversation == conversation.toString() } < MAX_SCHEDULES_PER_CONVERSATION) { "this conversation already has $MAX_SCHEDULES_PER_CONVERSATION schedules; delete one first" }
        val start = zoned(now())
        val rule = parseWhen(whenText, zone, start)
        val id = generateSequence { newToken(3) }.first { c -> items.none { it.id == c } }
        val s = Schedule(id, conversation.toString(), creator, canonicalWhen(whenText), text, now(), null, rule.next(start)!!.toInstant().toEpochMilli())
        items += s
        save()
        return s
    }

    @Synchronized fun list(conversation: Uuid) = items.filter { it.conversation == conversation.toString() }

    @Synchronized fun delete(conversation: Uuid, id: String): Boolean {
        val removed = items.removeAll { it.id == id.lowercase() && it.conversation == conversation.toString() }
        if (removed) save()
        return removed
    }

    @Synchronized fun disable(id: String) {
        items.replaceAll { if (it.id == id) it.copy(enabled = false) else it }
        save()
    }

    @Synchronized fun activeCount() = items.count { it.enabled }

    @Synchronized fun due(): List<Schedule> {
        val at = now()
        val fired = items.filter { it.enabled && it.nextRun <= at }
        if (fired.isEmpty()) return fired
        val base = zoned(at)
        for (s in fired) {
            // far-past "now" so a stored once-time still parses after it came due
            val next = parseWhen(s.whenText, zone, base.minusYears(1000)).next(base)
            if (next == null) items.remove(s) else items[items.indexOf(s)] = s.copy(lastRun = at, nextRun = next.toInstant().toEpochMilli())
        }
        save()
        return fired
    }

    fun describe(s: Schedule): String =
        "${s.id} ${s.whenText}${if (s.enabled) ", next ${zoned(s.nextRun).format(NEXT_RUN_FORMAT)}" else " (disabled)"}, by ${s.creator}: ${s.prompt.oneLine(60)}"
}

class ScheduleScope(val store: ScheduleStore, val conversation: Uuid, val creator: String)

private suspend fun withSchedules(b: AgentBackend, block: suspend (ScheduleScope) -> ToolReply): ToolReply = guarded {
    block(b.schedules ?: return@guarded ToolReply("schedules are not available", true))
}

fun scheduleTools(): List<ToolDef> = listOf(
    ToolDef(
        "create_schedule",
        "Schedule a recurring or one-off task for this conversation; it runs as an operator job with the prompt. when: $SCHEDULE_FORMS.",
        buildJsonObject {
            put("when", buildJsonObject { put("type", "string"); put("description", "Schedule, e.g. 'every weekday 09:00'") })
            put("prompt", buildJsonObject { put("type", "string"); put("description", "What to do each time (max $MAX_SCHEDULE_PROMPT_CODEPOINTS characters)") })
        },
        listOf("when", "prompt"), stdio = false, write = true,
    ) { b, a -> withSchedules(b) { scope ->
        val s = scope.store.create(scope.conversation, scope.creator, stringArg(a, "when") ?: "", stringArg(a, "prompt") ?: "")
        ToolReply("scheduled ${scope.store.describe(s)}")
    } },
    ToolDef("list_schedules", "List this conversation's schedules.", buildJsonObject {}, stdio = false) { b, _ -> withSchedules(b) { scope ->
        ToolReply(scope.store.list(scope.conversation).joinToString("\n") { scope.store.describe(it) }.ifEmpty { "(no schedules)" })
    } },
    ToolDef(
        "delete_schedule", "Delete one of this conversation's schedules by id.",
        buildJsonObject { put("id", buildJsonObject { put("type", "string"); put("description", "Schedule id") }) },
        listOf("id"), stdio = false, write = true,
    ) { b, a -> withSchedules(b) { scope ->
        val id = stringArg(a, "id") ?: return@withSchedules ToolReply("id is empty", true)
        if (scope.store.delete(scope.conversation, id)) ToolReply("deleted $id") else ToolReply("no schedule $id in this conversation", true)
    } },
)

val SCHEDULE_TOOL_NAMES = setOf("create_schedule", "list_schedules", "delete_schedule")
