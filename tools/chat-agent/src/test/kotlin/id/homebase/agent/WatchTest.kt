package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class WatchTest {
    private val owner = OdinId("owner.example.com")
    private val self = ChatProtocol.ConversationWithYourselfId

    private fun trig(text: String, bot: Boolean = false, identity: String = "owner.example.com") =
        shouldTrigger(text, Nickname("quagmire"), bot, identity)

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

    private fun msg(text: String, conv: Uuid = self, author: OdinId? = owner, id: Uuid = Uuid.random()) =
        ChatMsg(id, conv, author, text, 1L)

    private fun cfg() = AgentConfig(allowlist = Allowlist.default(owner))

    @Test
    fun allowlistRefusal() = runBlocking {
        val h = TestHarness(cfg())
        assertEquals("skip: conversation not allowed", h.handle(msg("@quagmire hi", conv = Uuid.random())))
        assertEquals("skip: author not allowed", h.handle(msg("@quagmire hi", author = OdinId("evil.example.com"))))
        assertEquals("skip: author not allowed", h.handle(msg("@quagmire hi", author = null)))
        assertEquals("skip: no trigger", h.handle(msg("hello")))
        assertEquals(0, h.brainRuns)
        assertTrue(h.replies.isEmpty())
    }

    @Test
    fun dedupe() = runBlocking {
        val h = TestHarness(cfg())
        val m = msg("@quagmire hi")
        assertEquals("replied", h.handle(m))
        assertEquals("seen", h.handle(m))
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
        assertEquals(5, f.readLines().size)
        assertFalse(ids[0] in s)
        assertTrue(ids[4] in s)
        val reloaded = ProcessedStore(f, cap = 3)
        assertTrue(ids[2] in reloaded && ids[4] in reloaded && ids[1] !in reloaded)
    }

    @Test
    fun processedStoreCompactsOnLoadAndAtTwiceTheCap() {
        val f = File.createTempFile("processed", ".txt").apply { deleteOnExit() }
        f.delete()
        val s = ProcessedStore(f, cap = 3)
        val ids = List(7) { Uuid.random() }
        ids.forEach(s::add)
        assertEquals(ids.takeLast(3).map { it.toString() }, f.readLines())
        f.writeText(ids.joinToString("\n", postfix = "\n"))
        ProcessedStore(f, cap = 3)
        assertEquals(ids.takeLast(3).map { it.toString() }, f.readLines())
    }

    @Test
    fun sameMillisecondSiblingIsNotLostAndNewestIsNotReplayed() {
        val cursor = SeenCursor(100L)
        fun m(t: Long) = ChatMsg(Uuid.random(), self, owner, "x", t)
        val first = m(200L)
        assertEquals(listOf(first.id), cursor.fresh(listOf(first, m(50L))).map { it.id })
        assertEquals(200L, cursor.position)
        assertTrue(cursor.fresh(listOf(first)).isEmpty())
        val sibling = m(200L)
        assertEquals(listOf(sibling.id), cursor.fresh(listOf(first, sibling)).map { it.id })
        assertTrue(cursor.fresh(listOf(first, sibling)).isEmpty())
    }

    @Test
    fun brainOutputHandling() = runBlocking {
        assertEquals("🤖 pong", brainReply(runBrain(Brain("echo pong"), "p")))
        assertNull(brainReply(runBrain(Brain("printf ''"), "p")))
        assertNull(brainReply(runBrain(Brain("echo NO_REPLY"), "p")))
        assertEquals("🤖 failed: exit 3", brainReply(runBrain(Brain("exit 3"), "p")))
        assertEquals("🤖 failed: timeout after 1s", brainReply(runBrain(Brain("sleep 999"), "p", timeoutMs = 1000)))
        assertEquals("🤖 stdin-ok", brainReply(runBrain(Brain("cat | sed 's/^prompt/stdin-ok/'"), "prompt")))
    }

    @Test
    fun failureAndSilenceThroughProcessor() = runBlocking {
        val failing = TestHarness(cfg(), outcome = BrainOutcome.Failed("exit 3"))
        val m = msg("@quagmire x")
        failing.handle(m)
        failing.processor.handleAll(emptyList())
        assertEquals(listOf("🤖 failed: exit 3"), failing.replies)
        val silent = TestHarness(cfg(), outcome = BrainOutcome.Output("NO_REPLY\n"))
        assertEquals("silent", silent.handle(msg("@quagmire x")))
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
        val h = TestHarness(cfg())
        val ms = List(3) { ChatMsg(Uuid.random(), self, owner, "@quagmire q$it", it.toLong()) }
        h.processor.handleAll(ms)
        assertEquals(1, h.brainRuns)
        assertEquals(1, h.replies.size)
        assertTrue(ms.all { h.prompts.single().contains("q${ms.indexOf(it)}") })
        assertEquals("seen", h.handle(ms[1]))
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
        val h = TestHarness(cfg(), limiter = RunLimiter(null, 0, 100))
        assertEquals("skip: rate limited", h.handle(msg("@quagmire hi")))
        assertEquals(0, h.brainRuns)
        assertTrue(h.replies.isEmpty())
    }

    @Test
    fun failureRetriedOnceThenReportedAndProcessed() = runBlocking {
        val store = ProcessedStore(null)
        val h = TestHarness(cfg(), store, outcome = BrainOutcome.Failed("exit 3"))
        val m = msg("@quagmire x")
        assertEquals("retry", h.handle(m))
        assertTrue(h.replies.isEmpty())
        assertFalse(m.id in store)
        assertEquals("failed", h.processor.handleAll(emptyList())[m.id])
        assertEquals(listOf("🤖 failed: exit 3"), h.replies)
        assertTrue(m.id in store)
        assertEquals(2, h.brainRuns)
    }

    @Test
    fun failureThenSuccessOnRetryAndSendFailureRetried() = runBlocking {
        val h = TestHarness(cfg(), outcomes = mutableListOf(BrainOutcome.Failed("x")), sendFailures = 0)
        val m = msg("@quagmire x")
        assertEquals("retry", h.handle(m))
        assertEquals("replied", h.processor.handleAll(emptyList())[m.id])
        assertEquals(listOf("🤖 pong"), h.replies)
        val s = TestHarness(cfg(), sendFailures = 1)
        val m2 = msg("@quagmire y")
        assertEquals("retry", s.handle(m2))
        assertEquals("replied", s.processor.handleAll(emptyList())[m2.id])
    }

    private val group = Uuid.random()

    private fun groupCfg() = AgentConfig(
        allowlist = parseConfig("bot=true", owner).allowlist.also {
            it.learn(listOf(ConversationInfo(group, "Team", listOf(OdinId("alice.example.com"), owner))))
        },
    )

    @Test
    fun seenMessageLoggedOnce() = runBlocking {
        val h = TestHarness(cfg(), outcome = BrainOutcome.Output("NO_REPLY"))
        val m = msg("hello")
        assertEquals("skip: no trigger", h.handle(m))
        assertEquals("seen", h.handle(m))
        assertEquals(1, h.logs.size)
    }

    @Test
    fun botRepliesIntoTriggeringGroup() = runBlocking {
        val h = TestHarness(groupCfg())
        assertEquals("replied", h.handle(msg("@quagmire hi", conv = group, author = OdinId("alice.example.com"))))
        assertEquals(listOf(group), h.replyTargets)
        assertEquals(listOf("pong"), h.replies)
    }

    @Test
    fun plainGroupWithoutExplicitListingRefused() = runBlocking {
        val h = TestHarness(delegateCfg(listed = false))
        assertEquals("skip: conversation not allowed", h.handle(msg("@quagmire hi", conv = group, author = alice)))
        assertEquals(0, h.brainRuns)
        assertTrue(h.replies.isEmpty())
    }

    @Test
    fun meRefusesMemberMode() {
        assertFailsWith<IllegalArgumentException> { parseConfig("allowConversations=member", owner, "me") }
        assertFailsWith<IllegalArgumentException> { parseConfig("allowConversations=self,member", owner, "me") }
        assertFailsWith<IllegalArgumentException> { Allowlist(setOf(self), setOf(owner), Kind.DELEGATE, memberMode = true) }
    }

    private val alice = OdinId("alice.example.com")

    private fun plainCfg(): AgentConfig {
        val cfg = parseConfig("allowConversations=self,$group", owner)
        assertEquals(Kind.PLAIN, cfg.allowlist.kind)
        cfg.allowlist.learn(listOf(ConversationInfo(group, "Team", listOf(alice, owner))))
        return cfg
    }

    @Test
    fun plainProfileKeepsOwnerOnlyNoGroupSendNoAway() = runBlocking {
        val flag = AwayFlag(null)
        val h = TestHarness(plainCfg(), away = flag)
        assertEquals("skip: author not allowed", h.handle(msg("@quagmire hi", conv = group, author = alice)))
        assertFalse(plainCfg().allowlist.allowsSend(group))
        assertEquals("skip: send not permitted in this conversation", h.handle(msg("@quagmire hi", conv = group, author = owner)))
        assertEquals("replied", h.handle(msg("@quagmire away")))
        assertFalse(flag.on)
        assertEquals(0, h.brainRuns.minus(1))
    }

    @Test
    fun botFlagOnMeProfileIsWarnedNotSilent() {
        assertEquals(1, parseConfig("bot=true", owner, "me").warnings.size)
        assertTrue(parseConfig("", owner, "me").warnings.isEmpty())
    }

    private fun delegateCfg(listed: Boolean = true): AgentConfig {
        val conf = if (listed) "allowConversations=self,$group" else ""
        val cfg = parseConfig(conf, owner, "me")
        cfg.allowlist.learn(listOf(ConversationInfo(group, "Team", listOf(alice, owner))))
        return cfg
    }

    private fun awayFlag() = AwayFlag(File.createTempFile("away", "").apply { delete(); deleteOnExit() })

    @Test
    fun awayToggleRepliesPersistsAndSkipsBrain() = runBlocking {
        val file = File.createTempFile("away", "").apply { delete(); deleteOnExit() }
        val flag = AwayFlag(file)
        val h = TestHarness(delegateCfg(), away = flag)
        assertEquals("away on", h.handle(msg("@Quagmire AWAY")))
        assertTrue(flag.on && file.exists() && AwayFlag(file).on)
        assertEquals("away off", h.handle(msg("@quagmire back")))
        assertFalse(flag.on || file.exists())
        assertEquals(listOf("$BOT_PREFIX away on", "$BOT_PREFIX away off"), h.replies)
        assertEquals(0, h.brainRuns)
    }

    @Test
    fun ownerMentionTriggersOnlyWhileAway() = runBlocking {
        val flag = awayFlag()
        val h = TestHarness(delegateCfg(), away = flag)
        val m1 = msg("@owner.example.com are you there", conv = group, author = alice)
        assertEquals("skip: no trigger", h.handle(m1))
        flag.on = true
        val m2 = msg("@owner.example.com hello", conv = group, author = alice)
        assertEquals("replied", h.handle(m2))
        assertEquals(listOf(group), h.replyTargets)
        assertTrue(h.prompts.single().contains("is away"))
        flag.on = false
        assertEquals("replied", h.handle(msg("@quagmire hi", conv = group, author = alice)))
        assertFalse(h.prompts.last().contains("is away"))
    }

    @Test
    fun nicknameSummonWhileAwayGetsAwayGuidance() = runBlocking {
        val flag = awayFlag().also { it.on = true }
        val h = TestHarness(delegateCfg(), away = flag)
        assertEquals("replied", h.handle(msg("@quagmire hi", conv = group, author = alice)))
        assertTrue(h.prompts.single().contains("is away"))
        assertTrue(h.prompts.single().contains(NO_REPLY))
    }

    @Test
    fun ownMessagesInGroupIgnoredEvenWhenAway() = runBlocking {
        val flag = awayFlag().also { it.on = true }
        val h = TestHarness(delegateCfg(), away = flag)
        assertEquals("skip: own message", h.handle(msg("@quagmire @owner.example.com hi", conv = group, author = owner)))
        assertEquals(0, h.brainRuns)
    }

    @Test
    fun noteToSelfStaysOwnerOnlyAndAwayMentionIgnoredThere() = runBlocking {
        val flag = awayFlag().also { it.on = true }
        val h = TestHarness(delegateCfg(), away = flag)
        assertEquals("skip: author not allowed", h.handle(msg("@quagmire hi", author = alice)))
        assertEquals("skip: no trigger", h.handle(msg("note @owner.example.com")))
    }

    @Test
    fun awayCommandOnlyFromOwnerInNoteToSelfExact() {
        assertEquals(true, awayCommand(" @quagmire  away ", "quagmire"))
        assertEquals(false, awayCommand("@QUAGMIRE Back", "quagmire"))
        assertNull(awayCommand("@quagmire away please", "quagmire"))
        assertNull(awayCommand("@quagmire", "quagmire"))
    }

    @Test
    fun disclosurePrefixes() {
        val me = delegateCfg().allowlist
        assertEquals("$BOT_PREFIX owner.example.com's AI assistant: pong", me.disclosure(group, "$BOT_PREFIX pong", owner))
        assertEquals("$BOT_PREFIX pong", me.disclosure(self, "$BOT_PREFIX pong", owner))
        val bot = parseConfig("bot=true", owner, "bot").allowlist
        assertEquals("$BOT_PREFIX pong", bot.disclosure(group, "$BOT_PREFIX pong", owner))
    }

    @Test
    fun meSendsOnlyToSelfOrExplicitlyListedGroup() {
        val listed = delegateCfg().allowlist
        listed.requireSend(self)
        listed.requireSend(group)
        assertFailsWith<IllegalArgumentException> { listed.requireSend(Uuid.random()) }
        assertFailsWith<IllegalArgumentException> { delegateCfg(listed = false).allowlist.requireSend(group) }
    }

    @Test
    fun messageArrivingAfterFirstPollIsHandled() = runBlocking {
        val h = TestHarness(cfg())
        val old = ChatMsg(Uuid.random(), self, owner, "hello", 1L)
        assertEquals("skip: no trigger", h.processor.handleAll(listOf(old))[old.id])
        val late = ChatMsg(Uuid.random(), self, owner, "@quagmire ping", 2L)
        val second = h.processor.handleAll(listOf(old, late))
        assertEquals("seen", second[old.id])
        assertEquals("replied", second[late.id])
        assertEquals(listOf("🤖 pong"), h.replies)
    }
}
