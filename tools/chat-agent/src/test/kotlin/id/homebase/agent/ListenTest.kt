package id.homebase.agent

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class ListenTest {
    private val self = OdinId("bot.example.com")
    private val op = OdinId("op.example.com")
    private val rando = OdinId("rando.example.com")
    private val room = Uuid.random()
    private val other = Uuid.random()
    private var n = 0L

    private fun cfg(listen: Set<Uuid> = setOf(room), kind: Kind = Kind.BOT, cooldownMs: Long = 60_000L, operatorBrain: String? = "full"): AgentConfig {
        val allow = Allowlist(setOf(room, other), if (kind == Kind.BOT) null else setOf(op), kind)
        val members = listOf(self, op, rando)
        allow.learn(listOf(ConversationInfo(room, "room", members), ConversationInfo(other, "other", members)))
        return AgentConfig(allowlist = allow, operators = setOf(op), operatorBrain = operatorBrain, listenRooms = listen, listenCooldownMs = cooldownMs, followUpMs = 0)
    }

    private fun harness(
        listen: Set<Uuid> = setOf(room),
        kind: Kind = Kind.BOT,
        outcome: BrainOutcome = BrainOutcome.Output("pong"),
        history: List<ChatMsg> = emptyList(),
    ) = TestHarness(cfg(listen = listen, kind = kind), identity = self.toString(), outcome = outcome, history = history)

    private fun addressed(prompt: String) = "not address you" !in prompt && "you only listen in" !in prompt

    private fun unprompted(prompt: String) = "output exactly PASS" in prompt && "does not address you" in prompt

    private fun msg(who: OdinId?, text: String, conversation: Uuid = room, raw: String? = null) =
        ChatMsg(Uuid.random(), conversation, who, text, ++n, sender = who, rawContent = raw)

    private fun replyTo(parent: ChatMsg) =
        """{"replyPreview":{"replyUniqueId":"${parent.id}","authorOdinId":"x","message":"m"},"message":"ok","version":1}"""

    @Test
    fun untaggedMessageInListenedRoomRunsLockedWithPassPrompt() = runBlocking<Unit> {
        val h = harness()
        val results = h.handleAll(listOf(msg(rando, "anyone know a good pizza place?")))
        assertEquals(listOf(Tier.LOCKED), h.tiers)
        assertTrue(unprompted(h.prompts.single()))
        assertEquals(listOf("pong"), h.replies)
        assertTrue(results.values.all { it == "replied" })
    }

    @Test
    fun untaggedMessageInUnlistedRoomDoesNotRun() = runBlocking<Unit> {
        val h = harness()
        assertEquals("skip: no trigger", h.handle(msg(rando, "hello", conversation = other)))
        assertEquals(0, h.brainRuns)
    }

    @Test
    fun ownAndRobotMessagesNeverTriggerInListenedRoom() = runBlocking<Unit> {
        val h = harness()
        assertEquals("skip: own message", h.handle(msg(self, "my own line")))
        assertEquals("skip: no trigger", h.handle(msg(rando, "🤖 beep")))
        assertEquals(0, h.brainRuns)
    }

    @Test
    fun listenRoomsIgnoredForDelegateAndPlainProfiles() = runBlocking<Unit> {
        val conf = "listenRooms=$room\nallowConversations=$room"
        val delegate = parseConfig(conf, OdinId("owner.example.com"), profile = DELEGATE_PROFILE)
        assertTrue(delegate.listenRooms.isEmpty())
        assertTrue(delegate.warnings.any { "listenRooms is ignored" in it })
        val bot = parseConfig("$conf\nbot=true\nlistenCooldown=5m", OdinId("owner.example.com"), profile = "bot")
        assertEquals(setOf(room), bot.listenRooms)
        assertEquals(300_000L, bot.listenCooldownMs)

        val h = harness(listen = emptySet(), kind = Kind.DELEGATE)
        assertEquals("skip: no trigger", h.handle(msg(op, "chatter")))
    }

    @Test
    fun unpromptedRunIsLockedEvenForOperatorSender() = runBlocking<Unit> {
        val h = harness()
        h.handle(msg(op, "thinking out loud"))
        assertEquals(listOf(Tier.LOCKED), h.tiers)
        h.handle(msg(op, "@quagmire do the thing"))
        assertEquals(listOf(Tier.LOCKED, Tier.OPERATOR), h.tiers)
    }

    @Test
    fun passIsNotPostedButMarkedProcessed() = runBlocking<Unit> {
        for (out in listOf("PASS", "  pass.\n", "\n PASS!! \n")) {
            val h = harness(outcome = BrainOutcome.Output(out))
            val m = msg(rando, "chatter")
            assertEquals("passed", h.handle(m))
            assertTrue(h.sends.isEmpty())
            assertTrue("listen: passed in $room" in h.logs)
            assertEquals("seen", h.handle(m))
        }
        val blank = harness(outcome = BrainOutcome.Output("  \n"))
        assertEquals("silent", blank.handle(msg(rando, "chatter")))
        assertTrue(blank.sends.isEmpty())
        assertFalse(isPass("PASSING on this"))
    }

    @Test
    fun cooldownSkipsThenAllowsAndTaggedBypasses() = runBlocking<Unit> {
        val h = harness()
        assertEquals("replied", h.handle(msg(rando, "one")))
        h.now += 30_000
        assertEquals("skip: listen cooldown", h.handle(msg(rando, "two")))
        assertEquals(1, h.brainRuns)
        assertEquals("replied", h.handle(msg(rando, "@quagmire three")))
        assertEquals(2, h.brainRuns)
        assertTrue(addressed(h.prompts.last()))
        h.now += 31_000
        assertEquals("replied", h.handle(msg(rando, "four")))
        assertEquals(3, h.brainRuns)
    }

    @Test
    fun severalUnpromptedMessagesInOnePollShareOneRun() = runBlocking<Unit> {
        val h = harness()
        val results = h.handleAll(listOf(msg(rando, "a1"), msg(op, "a2"), msg(rando, "a3")))
        assertEquals(1, h.brainRuns)
        assertEquals(3, results.size)
    }

    @Test
    fun replyToBotMessageTriggersInAnyAllowedConversation() = runBlocking<Unit> {
        val botLine = msg(self, "earlier bot answer", conversation = other)
        val h = harness(listen = emptySet(), history = listOf(botLine))
        val reply = msg(rando, "thanks, and also?", conversation = other, raw = replyTo(botLine))
        assertEquals("replied", h.handle(reply))
        assertEquals(listOf(Tier.LOCKED), h.tiers)
        assertTrue(addressed(h.prompts.single()))
        val opReply = msg(op, "and another thing", conversation = other, raw = replyTo(botLine))
        h.handle(opReply)
        assertEquals(Tier.OPERATOR, h.tiers.last())
    }

    @Test
    fun replyToSomeoneElsesOrUnknownMessageDoesNotTrigger() = runBlocking<Unit> {
        val human = msg(op, "a human line", conversation = other)
        val h = harness(listen = emptySet(), history = listOf(human))
        assertEquals("skip: no trigger", h.handle(msg(rando, "agreed", conversation = other, raw = replyTo(human))))
        val missing = msg(self, "not in window", conversation = other)
        assertEquals("skip: no trigger", h.handle(msg(rando, "what?", conversation = other, raw = replyTo(missing))))
        assertEquals(0, h.brainRuns)
    }

    @Test
    fun replyBypassesListenCooldown() = runBlocking<Unit> {
        val botLine = msg(self, "bot line")
        val h = harness(history = listOf(botLine))
        h.handle(msg(rando, "one"))
        assertEquals("replied", h.handle(msg(rando, "reply now", raw = replyTo(botLine))))
        assertEquals(2, h.brainRuns)
    }

    @Test
    fun delegateIsNotTriggeredByRepliesToOwnerMessages() = runBlocking<Unit> {
        val ownerLine = msg(self, "owner said this", conversation = other)
        val h = harness(listen = emptySet(), kind = Kind.DELEGATE, history = listOf(ownerLine))
        assertEquals("skip: no trigger", h.handle(msg(op, "replying to owner", conversation = other, raw = replyTo(ownerLine))))
        assertEquals(0, h.brainRuns)
    }
}
