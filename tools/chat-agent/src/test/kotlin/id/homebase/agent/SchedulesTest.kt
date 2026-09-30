package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SchedulesTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val self = OdinId("bot.example.com")
    private val op = OdinId("op.example.com")
    private val other = OdinId("other.example.com")
    private val rando = OdinId("rando.example.com")
    private val room = Uuid.random()
    private val room2 = Uuid.random()
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun at(text: String) = ZonedDateTime.parse("$text[Europe/Oslo]")
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private fun next(w: String, from: ZonedDateTime) = parseWhen(w, oslo, from).next(from)

    @Test
    fun grammarAndNextRun() {
        val from = at("2026-09-30T10:00:00+02:00")
        assertEquals(at("2026-09-30T10:30:00+02:00"), next("every 30m", from))
        assertEquals(at("2026-09-30T12:00:00+02:00"), next("EVERY 2h", from))
        assertEquals(at("2026-10-01T09:00:00+02:00"), next("daily 09:00", from))
        assertEquals(at("2026-09-30T18:05:00+02:00"), next("daily 18:05", from))
        assertEquals(at("2026-10-05T09:00:00+02:00"), next("every weekday 09:00", at("2026-10-02T10:00:00+02:00")))
        assertEquals(at("2026-10-03T08:00:00+02:00"), next("every  sat , sun 8:00", at("2026-10-02T10:00:00+02:00")))
        assertEquals(at("2026-10-05T23:00:00+02:00"), next("once 2026-10-05 23:00", from))
        assertEquals(null, When.Once(java.time.LocalDateTime.of(2026, 10, 5, 23, 0)).next(at("2026-10-06T00:00:00+02:00")))
    }

    @Test
    fun rejectsBadForms() {
        val from = at("2026-09-30T10:00:00+02:00")
        for (w in listOf("every 10m", "every 14m", "every 0h", "hourly", "daily 25:00", "daily 9:5", "every funday 09:00", "once 2020-01-01 10:00", "once 2026-13-01 10:00", "every weekday", "")) {
            assertFailsWith<IllegalArgumentException>(w) { parseWhen(w, oslo, from) }
        }
        assertEquals(15, (parseWhen("every 15m", oslo, from) as When.Interval).minutes)
        assertTrue(assertFailsWith<IllegalArgumentException> { parseWhen("nope", oslo, from) }.message!!.contains("forms:"))
    }

    @Test
    fun dstBoundaryKeepsWallClock() {
        assertEquals(at("2026-10-25T09:00:00+01:00"), next("daily 09:00", at("2026-10-24T10:00:00+02:00")))
        assertEquals(at("2026-10-26T09:00:00+01:00"), next("daily 09:00", at("2026-10-25T10:00:00+01:00")))
        assertEquals(at("2026-03-29T03:30:00+02:00"), next("daily 02:30", at("2026-03-28T10:00:00+01:00")))
        assertEquals(at("2026-03-30T02:30:00+02:00"), next("daily 02:30", at("2026-03-29T04:00:00+02:00")))
    }

    @Test
    fun weekdaySkipsWeekend() {
        val friEvening = at("2026-10-02T18:00:00+02:00")
        assertEquals(at("2026-10-05T09:00:00+02:00"), next("every weekday 09:00", friEvening))
        assertEquals(at("2026-10-02T18:30:00+02:00"), next("every weekday 18:30", friEvening))
    }

    @Test
    fun capsAndValidation() {
        var clock = ms(at("2026-09-30T10:00:00+02:00"))
        val store = ScheduleStore(null, oslo) { clock }
        repeat(MAX_SCHEDULES_PER_CONVERSATION) { store.create(room, op.toString(), "daily 09:00", "p$it") }
        assertTrue(assertFailsWith<IllegalArgumentException> { store.create(room, op.toString(), "daily 09:00", "x") }.message!!.contains("already has"))
        store.create(room2, op.toString(), "daily 09:00", "other room is fine")
        val fresh = ScheduleStore(null, oslo) { clock }
        fresh.create(room, op.toString(), "daily 09:00", "é".repeat(MAX_SCHEDULE_PROMPT_CODEPOINTS))
        assertFailsWith<IllegalArgumentException> { fresh.create(room2, op.toString(), "daily 09:00", "é".repeat(MAX_SCHEDULE_PROMPT_CODEPOINTS + 1)) }
        assertFailsWith<IllegalArgumentException> { fresh.create(room2, op.toString(), "daily 09:00", "  ") }
        assertFailsWith<IllegalArgumentException> { fresh.create(room2, op.toString(), "every 5m", "x") }
        assertTrue(fresh.list(room).single().id.matches(Regex("[0-9a-f]{6}")))
        clock += 1
    }

    @Test
    fun persistsAcrossReloadAndMissedFireRunsOnce() {
        val file = File.createTempFile("schedules", ".json")
        var clock = ms(at("2026-09-30T10:00:00+02:00"))
        val store = ScheduleStore(file, oslo) { clock }
        val s = store.create(room, op.toString(), "every 30m", "line1\nline2\t\"quoted\"")
        val once = store.create(room, op.toString(), "once 2026-10-01 08:00", "later")
        clock += 3 * 86_400_000L
        val reloaded = ScheduleStore(file, oslo) { clock }
        assertEquals(2, reloaded.activeCount())
        assertEquals("line1\nline2\t\"quoted\"", reloaded.list(room).first { it.id == s.id }.prompt)
        val due = reloaded.due()
        assertEquals(setOf(s.id, once.id), due.map { it.id }.toSet())
        assertTrue(reloaded.due().isEmpty())
        val after = reloaded.list(room).single()
        assertTrue(after.nextRun > clock && after.nextRun <= clock + 30 * 60_000L)
        assertEquals(clock, after.lastRun)
        assertEquals(1, ScheduleStore(file, oslo) { clock }.activeCount())
    }

    @Test
    fun corruptFileIsQuarantinedNotOverwritten() {
        val dir = tempDir("sched")
        val file = File(dir, "schedules.json").also { it.writeText("{not json") }
        val logs = mutableListOf<String>()
        val store = ScheduleStore(file, oslo, { logs += it }, { 1_790_000_000_000L })
        assertEquals(0, store.activeCount())
        assertTrue(logs.single().startsWith("WARNING") && logs.single().contains("schedules.json"))
        assertEquals("{not json", File(dir, "schedules.json.corrupt-1790000000000").readText())
        store.create(room, op.toString(), "daily 09:00", "p")
        assertEquals(1, ScheduleStore(file, oslo) { 1_790_000_000_000L }.activeCount())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun invalidTimezoneIsAConfigError() {
        assertFailsWith<IllegalArgumentException> { parseConfig("timezone=Nope/Zone", op) }
        assertEquals(oslo, parseConfig("timezone=Europe/Oslo", op).zone)
    }

    @Test
    fun promptCapCountsCodePoints() {
        val store = ScheduleStore(null, oslo) { 1_790_000_000_000L }
        assertFailsWith<IllegalArgumentException> { store.create(room, op.toString(), "daily 09:00", "😀".repeat(1999) + "😀😀") }
        val ok = "😀".repeat(MAX_SCHEDULE_PROMPT_CODEPOINTS)
        assertEquals(ok, store.create(room, op.toString(), "daily 09:00", ok).prompt)
    }

    @Test
    fun onceScheduleIsRemovedAfterFiring() {
        var clock = ms(at("2026-09-30T10:00:00+02:00"))
        val store = ScheduleStore(null, oslo) { clock }
        val s = store.create(room, op.toString(), "once 2026-10-01 08:00", "x")
        clock += 2 * 86_400_000L
        assertEquals(listOf(s.id), store.due().map { it.id })
        assertTrue(store.list(room).isEmpty())
    }

    @Test
    fun deleteIsConversationScoped() {
        val store = ScheduleStore(null, oslo) { 1_790_000_000_000L }
        val s = store.create(room, op.toString(), "daily 09:00", "p")
        assertFalse(store.delete(room2, s.id))
        assertEquals(1, store.list(room).size)
        assertTrue(store.delete(room, s.id.uppercase()))
    }

    private fun toolArgs(vararg kv: Pair<String, String>): JsonObject = buildJsonObject { kv.forEach { put(it.first, it.second) } }

    private class Backend(override val schedules: ScheduleScope?) : AgentBackend {
        override val allowlist: Allowlist = Allowlist.default(OdinId("owner.example.com"))
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) = emptyList<ChatMsg>()
        override suspend fun send(conversationId: Uuid, text: String, replyTo: id.homebase.chat.services.ReplyPreview?) = Uuid.random()
    }

    private fun tool(name: String) = CHAT_TOOLS.first { it.name == name }

    @Test
    fun toolsAreOperatorOnlyAndLeaseScoped() = runBlocking<Unit> {
        assertTrue(SCHEDULE_TOOL_NAMES.all { it in chatToolNames(Tier.OPERATOR) })
        assertTrue(SCHEDULE_TOOL_NAMES.none { it in chatToolNames(Tier.LOCKED) })
        assertTrue(scopedTools(chatToolNames(Tier.LOCKED), null).none { it.name in SCHEDULE_TOOL_NAMES })
        assertEquals(SCHEDULE_TOOL_NAMES, scopedTools(chatToolNames(Tier.OPERATOR), null).map { it.name }.filter { it in SCHEDULE_TOOL_NAMES }.toSet())

        val clock = ms(at("2026-09-30T10:00:00+02:00"))
        val store = ScheduleStore(null, oslo) { clock }
        val a = Backend(ScheduleScope(store, room, op.toString()))
        val b = Backend(ScheduleScope(store, room2, other.toString()))
        val created = tool("create_schedule").run(a, toolArgs("when" to "every weekday 09:00", "prompt" to "post the standup", "conversationId" to room2.toString()))
        assertFalse(created.isError, created.text)
        assertTrue(created.text.contains("next 2026-10-01 09:00 GMT+2") || created.text.contains("next 2026-10-01 09:00"), created.text)
        val id = store.list(room).single().id
        assertTrue(store.list(room2).isEmpty())
        assertTrue(tool("list_schedules").run(a, null).text.contains(id))
        assertEquals("(no schedules)", tool("list_schedules").run(b, null).text)
        assertTrue(tool("delete_schedule").run(b, toolArgs("id" to id)).isError)
        assertEquals(1, store.list(room).size)
        assertTrue(tool("create_schedule").run(a, toolArgs("when" to "sometimes", "prompt" to "x")).isError)
        assertTrue(tool("create_schedule").run(Backend(null), toolArgs("when" to "daily 09:00", "prompt" to "x")).isError)
        assertFalse(tool("delete_schedule").run(a, toolArgs("id" to id)).isError)
    }

    private class Rig(
        val self: OdinId, val op: OdinId, val other: OdinId, val rando: OdinId, val room: Uuid, val room2: Uuid, scope: CoroutineScope, oslo: ZoneId,
        maxJobs: Int = 10, operators: Set<OdinId>, val operatorWork: suspend (String) -> BrainOutcome,
    ) {
        var clock = 1_790_000_000_000L
        val store = ScheduleStore(null, oslo) { clock }
        val started = java.util.concurrent.CopyOnWriteArrayList<String>()
        val harness: TestHarness
        val runner = JobRunner(scope, JobLedger(null, maxJobs), prefix = "")

        init {
            val allow = Allowlist(setOf(room, room2), null, Kind.BOT)
            allow.learn(listOf(ConversationInfo(room, "r", listOf(self, op, other)), ConversationInfo(room2, "r2", listOf(self, rando))))
            val config = AgentConfig(allowlist = allow, operators = operators, operatorBrain = "x", maxJobsPerDay = maxJobs)
            harness = TestHarness(config, identity = self.toString(), jobs = runner, schedules = store, brainFn = { p, t, _ -> if (t == Tier.OPERATOR) { started += p; operatorWork(p) } else BrainOutcome.Output("pong") })
        }

        suspend fun say(conv: Uuid, sender: OdinId, text: String) = harness.handle(ChatMsg(Uuid.random(), conv, sender, text, clock++, sender = sender))
        fun advance(ms: Long) { clock += ms }
    }

    private fun rig(maxJobs: Int = 10, operators: Set<OdinId> = setOf(op, other), work: suspend (String) -> BrainOutcome = { BrainOutcome.Output("done") }) =
        Rig(self, op, other, rando, room, room2, scope, oslo, maxJobs, operators, work)

    private suspend fun until(what: String, cond: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!cond()) delay(20) }
        } catch (e: Exception) {
            error("timed out waiting for $what")
        }
    }

    @Test
    fun fireRunsOperatorJobWithFraming() = runBlocking<Unit> {
        val r = rig()
        val s = r.store.create(room, op.toString(), "every 30m", "post the standup")
        r.harness.processor.fireDue()
        assertTrue(r.started.isEmpty())
        r.advance(31 * 60_000L)
        r.harness.processor.fireDue()
        until("job") { r.started.isNotEmpty() }
        assertTrue(r.started.single().contains("Scheduled task ${s.id} created by $op: post the standup"), r.started.single())
        until("reply") { r.harness.replies.contains("done") }
        assertEquals(listOf("done"), r.harness.replies)
        assertTrue(r.harness.sends.all { it.first == room })
    }

    @Test
    fun creatorNoLongerOperatorDisablesWithoutJob() = runBlocking<Unit> {
        val r = rig(operators = setOf(other))
        val s = r.store.create(room, op.toString(), "every 30m", "x")
        r.advance(31 * 60_000L)
        r.harness.processor.fireDue()
        delay(200)
        assertTrue(r.started.isEmpty())
        assertTrue(r.harness.sends.isEmpty())
        assertFalse(r.store.list(room).single().enabled)
        assertTrue(r.harness.logs.any { it.contains("schedule ${s.id} disabled") })
        r.advance(31 * 60_000L)
        r.harness.processor.fireDue()
        delay(100)
        assertTrue(r.started.isEmpty())
    }

    @Test
    fun budgetExhaustedSkips() = runBlocking<Unit> {
        val r = rig(maxJobs = 1)
        r.runner.submit(room2, setOf(op.toString()), {}, {}) { BrainOutcome.Output("x") }
        val s = r.store.create(room, op.toString(), "every 30m", "x")
        r.advance(31 * 60_000L)
        r.harness.processor.fireDue()
        delay(200)
        assertTrue(r.started.isEmpty())
        assertTrue(r.harness.logs.any { it.contains("schedule ${s.id} skipped") && it.contains("daily job limit") })
        assertTrue(r.store.list(room).single().enabled)
    }

    @Test
    fun runningJobSkipsFire() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val r = rig { gate.await(); BrainOutcome.Output("slow") }
        r.say(room, op, "@quagmire long task")
        until("started") { r.started.isNotEmpty() }
        val s = r.store.create(room, op.toString(), "every 30m", "x")
        r.advance(31 * 60_000L)
        r.harness.processor.fireDue()
        assertEquals(1, r.started.size)
        assertTrue(r.harness.logs.any { it.contains("schedule ${s.id} skipped") && it.contains("still running") })
        gate.complete(Unit)
    }

    @Test
    fun commandsAreOperatorOnlyAndConversationScoped() = runBlocking<Unit> {
        val r = rig()
        val mine = r.store.create(room, op.toString(), "daily 09:00", "standup")
        val theirs = r.store.create(room2, rando.toString(), "daily 10:00", "elsewhere")
        assertEquals("schedule schedules", r.say(room, op, "@quagmire schedules"))
        val listing = r.harness.replies.last()
        assertTrue(listing.contains(mine.id) && listing.contains("standup") && !listing.contains(theirs.id), listing)
        r.say(room, other, "@quagmire unschedule ${theirs.id}")
        assertTrue(r.harness.replies.last().contains("no schedule"))
        assertEquals(1, r.store.list(room2).size)
        r.say(room, other, "@quagmire unschedule ${mine.id}")
        assertTrue(r.store.list(room).isEmpty())
        assertEquals(0, r.started.size)

        val result = r.say(room2, rando, "@quagmire schedules")
        assertFalse(result.startsWith("schedule"), result)
        assertTrue(r.store.list(room2).size == 1)
    }
}
