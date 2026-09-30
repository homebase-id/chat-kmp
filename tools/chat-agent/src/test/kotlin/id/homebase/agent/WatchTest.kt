package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class WatchTest {
    private val owner = OdinId("owner.example.com")
    private val self = ChatProtocol.ConversationWithYourselfId

    private fun trig(text: String, bot: Boolean = false, identity: String = "owner.example.com") =
        shouldTrigger(text, "quagmire", bot, identity)

    @Test
    fun nicknamePositives() {
        assertTrue(trig("@quagmire ping"))
        assertTrue(trig("hey @quagmire, ping"))
        assertTrue(trig("hey @QUAGMIRE"))
        assertTrue(trig("quagmire what's up"))
        assertTrue(trig("  Quagmire, help"))
        assertTrue(trig("ok\n@quagmire"))
        assertTrue(trig("(@quagmire)"))
        assertTrue(trig("quagmire"))
    }

    @Test
    fun nicknameNegatives() {
        assertFalse(trig("quagmireX hi"))
        assertFalse(trig("@quagmireX hi"))
        assertFalse(trig("hello quagmire"))
        assertFalse(trig("xquagmire hi"))
        assertFalse(trig("a@quagmire"))
        assertFalse(trig("@quagmire_bot"))
        assertFalse(trig("hello there"))
        assertFalse(trig(""))
    }

    @Test
    fun ownerMentionNeverTriggersWhenNotBot() {
        assertFalse(trig("@owner.example.com hi"))
        assertFalse(trig("@owner.example.com hi", bot = false))
    }

    @Test
    fun botIdentityMentionTriggers() {
        assertTrue(trig("@owner.example.com hi", bot = true))
        assertTrue(trig("@quagmire hi", bot = true))
        assertFalse(trig("@other.example.com hi", bot = true))
    }

    @Test
    fun robotPrefixNeverTriggers() {
        assertFalse(trig("🤖 you said quagmire"))
        assertFalse(trig("🤖 @quagmire"))
        assertFalse(trig("  🤖 pong @quagmire"))
        assertFalse(trig("🤖 @owner.example.com", bot = true))
    }

    @Test
    fun configDefaultsAndOverrides() {
        val d = parseConfig("", owner)
        assertEquals("quagmire", d.nickname)
        assertEquals("claude -p --model haiku --max-turns 3", d.brain)
        assertFalse(d.bot)
        assertEquals(setOf(self), d.allowlist.conversationIds)
        assertEquals(setOf(owner), d.allowlist.authors)
        val other = Uuid.random()
        val c = parseConfig(
            "# c\nnickname=zed\nbrain=echo hi | cat\nbot=true\nallowConversations=self, $other\nallowAuthors=a.example.com",
            owner,
        )
        assertEquals("zed", c.nickname)
        assertEquals("echo hi | cat", c.brain)
        assertTrue(c.bot)
        assertEquals(setOf(self, other), c.allowlist.conversationIds)
        assertEquals(setOf(OdinId("a.example.com")), c.allowlist.authors)
    }

    private fun msg(text: String, conv: Uuid = self, author: OdinId? = owner, id: Uuid = Uuid.random()) =
        ChatMsg(id, conv, author, text, 1L)

    private class Harness(
        config: AgentConfig,
        store: ProcessedStore = ProcessedStore(null),
        outcome: BrainOutcome = BrainOutcome.Output("pong"),
        limiter: RunLimiter? = null,
        val outcomes: MutableList<BrainOutcome> = mutableListOf(),
        var sendFailures: Int = 0,
    ) {
        val replies = mutableListOf<String>()
        var brainRuns = 0
        val prompts = mutableListOf<String>()
        val processor = WatchProcessor(
            config, "owner.example.com", store,
            history = { emptyList() },
            brain = { brainRuns++; prompts += it; outcomes.removeFirstOrNull() ?: outcome },
            reply = { _, t -> if (sendFailures > 0) { sendFailures--; error("boom") }; replies += t },
            log = {},
            limiter = limiter ?: RunLimiter(null, config.maxRunsPerHour, config.maxRunsPerDay),
        )
    }

    private fun cfg(bot: Boolean = false) = AgentConfig(bot = bot, allowlist = Allowlist.default(owner))

    @Test
    fun allowlistRefusal() = runBlocking {
        val h = Harness(cfg())
        assertEquals("skip: conversation not allowed", h.processor.handle(msg("@quagmire hi", conv = Uuid.random())))
        assertEquals("skip: author not allowed", h.processor.handle(msg("@quagmire hi", author = OdinId("evil.example.com"))))
        assertEquals("skip: author not allowed", h.processor.handle(msg("@quagmire hi", author = null)))
        assertEquals("skip: no trigger", h.processor.handle(msg("hello")))
        assertEquals(0, h.brainRuns)
        assertTrue(h.replies.isEmpty())
    }

    @Test
    fun dedupe() = runBlocking {
        val h = Harness(cfg())
        val m = msg("@quagmire hi")
        assertEquals("replied", h.processor.handle(m))
        assertEquals("seen", h.processor.handle(m))
        assertEquals(listOf("🤖 pong"), h.replies)
        assertEquals(1, h.brainRuns)
    }

    @Test
    fun processedStoreCapsAndPersists() {
        val f = File.createTempFile("processed", ".txt").apply { deleteOnExit() }
        f.delete()
        val s = ProcessedStore(f, cap = 3)
        val ids = List(5) { Uuid.random() }
        ids.forEach(s::add)
        assertEquals(3, s.size)
        assertFalse(ids[0] in s)
        assertTrue(ids[4] in s)
        val reloaded = ProcessedStore(f, cap = 3)
        assertTrue(ids[2] in reloaded && ids[4] in reloaded && ids[1] !in reloaded)
    }

    @Test
    fun brainOutputHandling() = runBlocking {
        assertEquals("🤖 pong", brainReply(runBrain("echo pong", "p")))
        assertNull(brainReply(runBrain("printf ''", "p")))
        assertNull(brainReply(runBrain("echo NO_REPLY", "p")))
        assertEquals("🤖 failed: exit 3", brainReply(runBrain("exit 3", "p")))
        assertEquals("🤖 failed: timeout after 1s", brainReply(runBrain("sleep 999", "p", timeoutMs = 1000)))
        assertEquals("🤖 stdin-ok", brainReply(runBrain("cat | sed 's/^prompt/stdin-ok/'", "prompt")))
    }

    @Test
    fun failureAndSilenceThroughProcessor() = runBlocking {
        val failing = Harness(cfg(), outcome = BrainOutcome.Failed("exit 3"))
        val m = msg("@quagmire x")
        failing.processor.handle(m)
        failing.processor.handleAll(emptyList())
        assertEquals(listOf("🤖 failed: exit 3"), failing.replies)
        val silent = Harness(cfg(), outcome = BrainOutcome.Output("NO_REPLY\n"))
        assertEquals("silent", silent.processor.handle(msg("@quagmire x")))
        assertTrue(silent.replies.isEmpty())
    }

    @Test
    fun promptIncludesTriggerAndLastTenTruncated() {
        val trigger = msg("@quagmire " + "x".repeat(5000))
        val hist = List(15) { ChatMsg(Uuid.random(), self, owner, "m$it", it.toLong()) } + trigger
        val p = buildPrompt(listOf(trigger), hist)
        assertFalse(p.contains("m4]") || p.contains("] m4\n"))
        assertTrue(p.contains("] m5\n") && p.contains("] m14\n"))
        assertTrue(p.length < 2500)
    }

    @Test
    fun coalescesTriggersPerConversation() = runBlocking {
        val h = Harness(cfg())
        val ms = List(3) { ChatMsg(Uuid.random(), self, owner, "@quagmire q$it", it.toLong()) }
        h.processor.handleAll(ms)
        assertEquals(1, h.brainRuns)
        assertEquals(1, h.replies.size)
        assertTrue(ms.all { h.prompts.single().contains("q${ms.indexOf(it)}") })
        assertEquals("seen", h.processor.handle(ms[1]))
    }

    @Test
    fun rateCapsHourAndDayPersisted() {
        val f = File.createTempFile("runs", ".txt").apply { deleteOnExit() }
        var t = 1_000_000_000L
        fun lim(h: Int, d: Int) = RunLimiter(f, h, d) { t }
        val a = setOf("a")
        var l = lim(2, 3)
        assertTrue(l.allows(a)); l.record(a)
        assertTrue(l.allows(a)); l.record(a)
        assertFalse(l.allows(a))
        assertTrue(l.allows(setOf("b")))
        l = lim(2, 3)
        assertFalse(l.allows(a))
        t += 3_700_000L
        assertTrue(l.allows(a)); l.record(a)
        assertFalse(l.allows(setOf("b")))
        t += 86_400_000L
        assertTrue(lim(2, 3).allows(a))
    }

    @Test
    fun overCapIsSilent() = runBlocking {
        val h = Harness(cfg(), limiter = RunLimiter(null, 0, 100))
        assertEquals("skip: rate limited", h.processor.handle(msg("@quagmire hi")))
        assertEquals(0, h.brainRuns)
        assertTrue(h.replies.isEmpty())
    }

    @Test
    fun failureRetriedOnceThenReportedAndProcessed() = runBlocking {
        val store = ProcessedStore(null)
        val h = Harness(cfg(), store, outcome = BrainOutcome.Failed("exit 3"))
        val m = msg("@quagmire x")
        assertEquals("retry", h.processor.handle(m))
        assertTrue(h.replies.isEmpty())
        assertFalse(m.id in store)
        assertEquals("failed", h.processor.handleAll(emptyList())[m.id])
        assertEquals(listOf("🤖 failed: exit 3"), h.replies)
        assertTrue(m.id in store)
        assertEquals(2, h.brainRuns)
    }

    @Test
    fun failureThenSuccessOnRetryAndSendFailureRetried() = runBlocking {
        val h = Harness(cfg(), outcomes = mutableListOf(BrainOutcome.Failed("x")), sendFailures = 0)
        val m = msg("@quagmire x")
        assertEquals("retry", h.processor.handle(m))
        assertEquals("replied", h.processor.handleAll(emptyList())[m.id])
        assertEquals(listOf("🤖 pong"), h.replies)
        val s = Harness(cfg(), sendFailures = 1)
        val m2 = msg("@quagmire y")
        assertEquals("retry", s.processor.handle(m2))
        assertEquals("replied", s.processor.handleAll(emptyList())[m2.id])
    }
}
