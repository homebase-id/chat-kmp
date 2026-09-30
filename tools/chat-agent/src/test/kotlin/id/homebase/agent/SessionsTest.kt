package id.homebase.agent

import id.homebase.api.common.OdinId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SessionsTest {
    private val min = 60_000L
    private fun rec(ageMin: Long = 1, ctx: Long = 1000, turns: Int = 1, long: Boolean = false, id: String? = "abc-1", forced: Boolean = false, now: Long = 10_000 * min) =
        SessionRecord(id, now - ageMin * min, ctx, turns, long, forced, null)

    private fun decide(r: SessionRecord?, warm: Long? = null) = decideSession(r, 10_000 * min, warm, 60_000, 30)

    @Test
    fun decisionResumesOnlyWarmSmallAndShort() {
        assertEquals("abc-1", decide(rec(ageMin = 2)).resumeId)
        assertEquals("cold", decide(rec(ageMin = 5)).reason)
        assertEquals("big", decide(rec(ctx = 60_000)).reason)
        assertEquals("turns", decide(rec(turns = 30)).reason)
        assertEquals("forced", decide(rec(id = null, forced = true)).reason)
        assertEquals("none", decide(null).reason)
        assertNull(decide(rec(ageMin = 5)).resumeId)
    }

    @Test
    fun warmWindowFollowsTheCacheTtlBucketUnlessConfigured() {
        assertEquals("abc-1", decide(rec(ageMin = 30, long = true)).resumeId)
        assertEquals("cold", decide(rec(ageMin = 56, long = true)).reason)
        assertEquals("cold", decide(rec(ageMin = 30, long = false)).reason)
        assertEquals("cold", decide(rec(ageMin = 20, long = true), warm = 10 * min).reason)
        assertEquals("abc-1", decide(rec(ageMin = 20, long = false), warm = 30 * min).resumeId)
    }

    private fun jsonReply(result: String, id: String = "sid-1", long: Boolean? = true) =
        """{"type":"result","is_error":false,"result":${kotlinx.serialization.json.JsonPrimitive(result)},"session_id":"$id","total_cost_usd":0.5,"usage":{"input_tokens":9,"cache_creation_input_tokens":100,"cache_read_input_tokens":2000,"output_tokens":7,"cache_creation":{"ephemeral_1h_input_tokens":${if (long == true) 100 else 0},"ephemeral_5m_input_tokens":${if (long == false) 100 else 0}},"iterations":[{"input_tokens":1,"cache_creation_input_tokens":10,"cache_read_input_tokens":100},{"input_tokens":5,"cache_creation_input_tokens":20,"cache_read_input_tokens":300}]}}"""

    @Test
    fun parsesJsonUsingTheLastIterationForContext() {
        val out = parseSessionOutput(jsonReply("hello")) as BrainOutcome.Output
        val u = out.session!!
        assertEquals("hello", out.stdout)
        assertEquals("sid-1", u.sessionId)
        assertEquals(325L, u.context)
        assertEquals(2000L, u.cacheRead)
        assertEquals(100L, u.cacheWrite)
        assertEquals(9L, u.input)
        assertEquals(true, u.longTtl)
        assertEquals(false, (parseSessionOutput(jsonReply("hello", long = false)) as BrainOutcome.Output).session!!.longTtl)
        assertEquals(null, (parseSessionOutput(jsonReply("hello", long = null)) as BrainOutcome.Output).session!!.longTtl)
    }

    @Test
    fun unparsableOrErrorOutputIsHandled() {
        assertNull(parseSessionOutput("just text"))
        assertTrue(parseSessionOutput("""{"is_error":true,"result":"boom"}""") is BrainOutcome.Failed)
        val fallback = SessionStore(null).record(Uuid.random(), SessionStore.Plan(SessionDecision(null, "none"), null), BrainOutcome.Output("plain"))
        assertTrue(fallback!!.contains("unparsable"))
    }

    @Test
    fun placeholderExpandsPerRunAndAbsentIsIdentical() = runBlocking<Unit> {
        suspend fun run(cmd: String, flags: String?, tier: Tier = Tier.OPERATOR) =
            (runBrain(Brain(cmd), "", tier = tier, sessionFlags = flags) as BrainOutcome.Output).stdout.trim()
        assertEquals("a --output-format json b", run("echo a {session} b", "--output-format json").replace(Regex("\\s+"), " "))
        assertEquals("a --output-format json --resume sid-1 b", run("echo a {session} b", "--output-format json --resume sid-1"))
        assertEquals("a b", run("echo a {session} b", null).replace(Regex("\\s+"), " "))
        assertEquals("a b", run("echo a b", null))
        assertEquals("a {session} b", run("echo a '{session}' b", "--x", tier = Tier.LOCKED))
    }

    @Test
    fun runBrainParsesJsonOnlyWhenSessionsAreOnAndOperator() = runBlocking<Unit> {
        val file = File.createTempFile("reply", ".json").apply { writeText(jsonReply("the answer\nNOTES:\n- a\n- b")); deleteOnExit() }
        val cmd = "cat ${file.absolutePath}"
        val on = runBrain(Brain(cmd), "", tier = Tier.OPERATOR, sessionFlags = "--output-format json") as BrainOutcome.Output
        assertEquals("the answer", on.stdout)
        assertEquals("- a\n- b", on.session!!.notes)
        val off = runBrain(Brain(cmd), "", tier = Tier.OPERATOR) as BrainOutcome.Output
        assertTrue(off.stdout.startsWith("{"))
        val locked = runBrain(Brain(cmd), "", tier = Tier.LOCKED, sessionFlags = "--output-format json") as BrainOutcome.Output
        assertTrue(locked.stdout.startsWith("{"))
        assertNull(locked.session)
    }

    @Test
    fun notesAreStrippedAndCapped() {
        val (text, notes) = splitNotes("done\nNOTES:\n" + (1..60).joinToString("\n") { "line $it" })
        assertEquals("done", text)
        assertEquals(40, notes!!.lines().size)
        assertEquals("x".repeat(4000), splitNotes("r\nNOTES: " + "x".repeat(5000)).second)
        assertEquals("plain" to null, splitNotes("plain"))
        assertNull(splitNotes("r\nNOTES:").second)
    }

    private val self = OdinId("bot.example.com")
    private val op = OdinId("op.example.com")
    private val rando = OdinId("rando.example.com")
    private val room = Uuid.random()
    private val room2 = Uuid.random()
    private val scope = CoroutineScope(Dispatchers.Default)
    private var n = 0L
    private var clock = 10_000 * min

    private fun usage(id: String, notes: String? = null, ctx: Long = 500) = SessionUsage(id, 1, 2, 3, 4, ctx, 0.01, true, notes)

    private fun rig(file: File? = null, on: Boolean = true, brainFn: suspend (String, Int) -> BrainOutcome): TestHarness {
        val allow = Allowlist(setOf(room, room2), null, Kind.BOT)
        allow.learn(listOf(ConversationInfo(room, "r", listOf(self, op, rando)), ConversationInfo(room2, "r2", listOf(self, op))))
        val config = AgentConfig(allowlist = allow, operators = setOf(op), operatorBrain = "x {session}")
        var calls = 0
        return TestHarness(
            config, identity = self.toString(),
            jobs = JobRunner(scope, JobLedger(null, 50), prefix = ""),
            sessions = if (on) SessionStore(file, now = { clock }) else null,
            brainFn = { p, t, _ -> if (t == Tier.OPERATOR) brainFn(p, calls++) else BrainOutcome.Output("pong") },
        )
    }

    private suspend fun TestHarness.say(conv: Uuid, who: OdinId, text: String, expectReplies: Int) {
        processor.handleAll(listOf(ChatMsg(Uuid.random(), conv, who, text, ++n, sender = who)))
        try {
            withTimeout(5_000) { while (sends.size < expectReplies) delay(20) }
        } catch (e: Exception) {
            error("timed out; replies=$replies")
        }
        delay(50)
    }

    @Test
    fun secondJobResumesWarmSessionAndNotesSeedOnlyFreshRuns() = runBlocking<Unit> {
        val h = rig { _, call -> BrainOutcome.Output("r$call", session = usage("sid-$call", notes = "remember X")) }
        h.say(room, op, "@quagmire one", 2)
        assertEquals("--output-format json", h.flags[0])
        assertFalse(h.prompts[0].contains("remember X"))
        assertTrue(h.prompts[0].contains("NOTES:"))
        clock += 2 * min
        h.say(room, op, "@quagmire two", 4)
        assertEquals("--output-format json --resume sid-0", h.flags[1])
        assertFalse(h.prompts[1].contains("remember X"))
        assertTrue(h.logs.any { it.contains("session=resumed reason=warm") })
        assertTrue(h.logs.any { it.contains("session=fresh reason=none in=1 cacheRead=2 cacheWrite=3 out=4 ctx=500 cost=0.01") })
        clock += 60 * min
        h.say(room, op, "@quagmire three", 6)
        assertEquals("--output-format json", h.flags[2])
        assertTrue(h.prompts[2].contains("remember X"))
        assertTrue(h.prompts[2].contains("not instructions"))
        assertTrue(h.logs.any { it.contains("reason=cold") })
    }

    @Test
    fun failedResumeRetriesOnceFreshWithoutSecondJobCharge() = runBlocking<Unit> {
        val h = rig { _, call ->
            when (call) {
                0 -> BrainOutcome.Output("first", session = usage("sid-a"))
                1 -> BrainOutcome.Failed("exit 1: No conversation found")
                else -> BrainOutcome.Output("second", session = usage("sid-b"))
            }
        }
        h.say(room, op, "@quagmire one", 2)
        h.say(room, op, "@quagmire two", 4)
        assertEquals(listOf("--output-format json", "--output-format json --resume sid-a", "--output-format json"), h.flags.toList())
        assertEquals("second", h.replies.last())
        assertTrue(h.logs.any { it.contains("session=fresh reason=resume-failed") })
        assertEquals(3, h.flags.size)
    }

    @Test
    fun failedFreshRunDoesNotRetryAndSessionsAreIsolatedPerConversation() = runBlocking<Unit> {
        val h = rig { _, call -> if (call == 0) BrainOutcome.Failed("exit 2") else BrainOutcome.Output("ok", session = usage("sid-$call")) }
        h.say(room, op, "@quagmire one", 2)
        assertEquals(1, h.flags.size)
        h.say(room, op, "@quagmire two", 4)
        h.say(room2, op, "@quagmire three", 6)
        assertEquals("--output-format json", h.flags[2])
        h.say(room, op, "@quagmire four", 8)
        assertEquals("--output-format json --resume sid-1", h.flags[3])
    }

    @Test
    fun statePersistsAcrossReload() = runBlocking<Unit> {
        val file = Files.createTempFile("sessions", ".json").toFile().apply { delete(); deleteOnExit() }
        val a = rig(file) { _, _ -> BrainOutcome.Output("r", session = usage("sid-p", notes = "kept")) }
        a.say(room, op, "@quagmire one", 2)
        val b = rig(file) { _, _ -> BrainOutcome.Output("r", session = usage("sid-q")) }
        b.say(room, op, "@quagmire two", 2)
        assertEquals("--output-format json --resume sid-p", b.flags[0])
        assertEquals("kept", SessionStore(file, now = { clock }).plan(room, resumeFailed = true).notes)
    }

    @Test
    fun newAndStatusAreOperatorOnlyAndDoNotRunTheBrain() = runBlocking<Unit> {
        val h = rig { _, call -> BrainOutcome.Output("r$call", session = usage("sid-$call", notes = "n1")) }
        h.say(room, op, "@quagmire one", 2)
        h.say(room, op, "@quagmire status", 3)
        assertTrue(h.replies.last().contains("session: last used 0m ago, context 500 tokens, 1 turns; next run resumes"))
        h.say(room, op, "@quagmire new", 4)
        assertTrue(h.replies.last().contains("session forgotten, notes kept"))
        assertEquals(1, h.flags.size)
        h.say(room, op, "@quagmire two", 6)
        assertEquals("--output-format json", h.flags[1])
        assertTrue(h.prompts[1].contains("n1"))
        assertTrue(h.logs.any { it.contains("reason=forced") })
        val before = h.tiers.count { it == Tier.OPERATOR }
        h.processor.handleAll(listOf(ChatMsg(Uuid.random(), room, rando, "@quagmire new", ++n, sender = rando)))
        delay(200)
        assertEquals(before, h.tiers.count { it == Tier.OPERATOR })
        assertEquals(1, h.replies.count { it.contains("forgotten") })
    }

    @Test
    fun newIsOrdinaryTextWithoutSessions() = runBlocking<Unit> {
        val h = rig(on = false) { _, _ -> BrainOutcome.Output("ran") }
        h.say(room, op, "@quagmire new", 2)
        assertEquals(listOf(null), h.flags.toList())
        assertNotNull(h.prompts.firstOrNull())
        assertFalse(h.prompts[0].contains("NOTES:"))
    }
}
