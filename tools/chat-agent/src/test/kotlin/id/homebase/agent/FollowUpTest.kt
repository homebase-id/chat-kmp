package id.homebase.agent

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class FollowUpTest {
    private val self = OdinId("bot.example.com")
    private val op = OdinId("op.example.com")
    private val rando = OdinId("rando.example.com")
    private val room = Uuid.random()
    private val dm = Uuid.random()
    private var n = 0L

    private fun cfg(kind: Kind = Kind.BOT, followUpMs: Long = 180_000L, listen: Set<Uuid> = emptySet()): AgentConfig {
        val allow = Allowlist(setOf(room, dm), if (kind == Kind.BOT) null else setOf(op), kind)
        allow.learn(listOf(ConversationInfo(room, "room", listOf(self, op, rando)), ConversationInfo(dm, "dm", listOf(self, rando))))
        return AgentConfig(allowlist = allow, operators = setOf(op), operatorBrain = "full", listenRooms = listen, followUpMs = followUpMs)
    }

    private fun harness(config: AgentConfig = cfg(), outcome: BrainOutcome = BrainOutcome.Output("pong")) =
        TestHarness(config, identity = self.toString(), outcome = outcome)

    private fun msg(who: OdinId, text: String, conversation: Uuid = room) =
        ChatMsg(Uuid.random(), conversation, who, text, ++n, sender = who)

    private suspend fun TestHarness.opened(who: OdinId = rando) {
        assertEquals("replied", handle(msg(who, "@quagmire hi")))
    }

    private fun followUpPrompt(prompt: String) = "just talking to you" in prompt && "output exactly PASS" in prompt

    @Test
    fun followUpWithinWindowRunsAndPassPostsNothing() = runBlocking<Unit> {
        val h = harness()
        h.opened()
        h.now += 60_000
        assertEquals("replied", h.handle(msg(rando, "and one more thing")))
        assertTrue(followUpPrompt(h.prompts.last()))
        assertEquals(2, h.sends.size)
        h.outcome = BrainOutcome.Output("PASS")
        h.now += 60_000
        assertEquals("passed", h.handle(msg(rando, "talking to myself")))
        assertEquals(2, h.sends.size)
        assertTrue("follow-up: passed in $room" in h.logs)
    }

    @Test
    fun outsideWindowDoesNotTrigger() = runBlocking<Unit> {
        val h = harness()
        h.opened()
        h.now += 180_001
        assertEquals("skip: no trigger", h.handle(msg(rando, "late")))
        assertEquals(1, h.brainRuns)
    }

    @Test
    fun otherSenderDoesNotTrigger() = runBlocking<Unit> {
        val h = harness()
        h.opened()
        assertEquals("skip: no trigger", h.handle(msg(op, "not for you")))
        assertEquals(1, h.brainRuns)
    }

    @Test
    fun tierFollowsTheSender() = runBlocking<Unit> {
        val h = harness()
        h.opened(op)
        h.opened(rando)
        h.handle(msg(op, "follow up"))
        h.handle(msg(rando, "follow up"))
        assertEquals(listOf(Tier.OPERATOR, Tier.LOCKED, Tier.OPERATOR, Tier.LOCKED), h.tiers)
    }

    @Test
    fun botReplyResetsTheWindow() = runBlocking<Unit> {
        val h = harness()
        h.opened()
        h.now += 150_000
        assertEquals("replied", h.handle(msg(rando, "first")))
        h.now += 150_000
        assertEquals("replied", h.handle(msg(rando, "second")))
        h.now += 180_001
        assertEquals("skip: no trigger", h.handle(msg(rando, "third")))
    }

    @Test
    fun meProfileNeverFollowsUp() = runBlocking<Unit> {
        val h = harness(cfg(kind = Kind.DELEGATE))
        assertEquals("skip: no trigger", h.handle(msg(rando, "hello")))
        assertEquals(parseConfig("bot=true\nfollowUp=90s", OdinId("o.example.com"), profile = "bot").followUpMs, 90_000L)
    }

    @Test
    fun zeroDisables() = runBlocking<Unit> {
        assertEquals(0L, parseConfig("bot=true\nfollowUp=0", OdinId("o.example.com"), profile = "bot").followUpMs)
        val h = harness(cfg(followUpMs = 0))
        h.opened()
        assertEquals("skip: no trigger", h.handle(msg(rando, "more")))
    }

    @Test
    fun listenCooldownDoesNotBlockFollowUp() = runBlocking<Unit> {
        val h = harness(cfg(listen = setOf(room)))
        assertEquals("replied", h.handle(msg(rando, "chatter")))
        h.now += 10_000
        assertEquals("skip: listen cooldown", h.handle(msg(op, "chatter")))
        assertEquals("replied", h.handle(msg(rando, "continuing")))
        assertTrue(followUpPrompt(h.prompts.last()))
    }

    @Test
    fun failedFollowUpPostsNothing() = runBlocking<Unit> {
        val h = harness()
        h.opened()
        h.outcome = BrainOutcome.Failed("boom")
        assertEquals("failed", h.handle(msg(rando, "more")))
        assertEquals(1, h.sends.size)
    }

    @Test
    fun addressedWinsAndMergesWithFollowUp() = runBlocking<Unit> {
        val h = harness()
        h.opened()
        h.outcome = BrainOutcome.Output("PASS")
        h.handleAll(listOf(msg(rando, "more"), msg(rando, "@quagmire answer please")))
        assertEquals(2, h.brainRuns)
        assertTrue(!followUpPrompt(h.prompts.last()))
        assertEquals(2, h.sends.size)
    }

    @Test
    fun directConversationsAreUnaffected() = runBlocking<Unit> {
        val h = harness()
        assertEquals("replied", h.handle(msg(rando, "hello", conversation = dm)))
        assertTrue(!followUpPrompt(h.prompts.single()))
    }

    @Test
    fun mixedSendersShareOneLockedRunAndEachWindowRefreshes() = runBlocking<Unit> {
        val h = harness()
        h.opened(op)
        h.opened(rando)
        h.now += 100_000
        h.tiers.clear()
        h.handleAll(listOf(msg(op, "a"), msg(rando, "b")))
        assertEquals(listOf(Tier.LOCKED), h.tiers)
        assertTrue(followUpPrompt(h.prompts.last()))
        h.now += 150_000
        assertEquals("replied", h.handle(msg(op, "c")))
        assertEquals("replied", h.handle(msg(rando, "d")))
    }
}
