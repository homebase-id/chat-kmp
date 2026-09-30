package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.XorIdUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class DirectChatTest {
    private val bot = OdinId("bot.example.com")
    private val alice = OdinId("alice.example.com")
    private val bob = OdinId("bob.example.com")
    private val dm = XorIdUtil.getNewXorId(bot.domainName, alice.domainName)
    private val group = Uuid.random()

    private fun config(persona: String? = null, perHour: Int = 20) = parseConfig(
        buildString {
            appendLine("bot=true")
            appendLine("maxRunsPerHour=$perHour")
            if (persona != null) appendLine("persona=$persona")
        },
        bot,
    )

    private fun msg(conv: Uuid, author: OdinId, text: String, sender: OdinId? = author) =
        ChatMsg(Uuid.random(), conv, author, text, 1L, sender = sender)

    private class H(val config: AgentConfig) {
        val replies = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        val p = WatchProcessor(
            config, "bot.example.com", ProcessedStore(null),
            history = { emptyList() },
            brain = { p, _ -> prompts += p; BrainOutcome.Output("pong") },
            reply = { _, t -> replies += t },
            log = {},
        )
    }

    private fun harness(persona: String? = null, perHour: Int = 20) = H(config(persona, perHour)).also {
        it.config.allowlist.learn(listOf(ConversationInfo(group, "Team", listOf(bot, alice, bob))))
        it.config.allowlist.learnDerived(ConversationInfo(dm, alice.toString(), listOf(bot, alice)))
    }

    @Test
    fun deriveUnknownDirectChat() {
        val cfg = config()
        val fromAlice = msg(dm, alice, "hi")
        val stranger = msg(Uuid.random(), bob, "hi")
        val derived = deriveDirectChats(bot, listOf(fromAlice, stranger, msg(dm, alice, "again")), cfg.allowlist)
        assertEquals(2, derived.size)
        assertTrue(cfg.allowlist.allowsConversation(dm))
        assertFalse(cfg.allowlist.allowsConversation(stranger.conversationId))
        assertEquals(listOf(bot, alice), cfg.allowlist.info(dm)?.members)
        assertTrue(cfg.allowlist.isDirect(dm))
        assertTrue(cfg.allowlist.allowsSend(dm))
    }

    @Test
    fun ownMessageInUnknownChatNotDerived() {
        val cfg = config()
        assertTrue(deriveDirectChats(bot, listOf(msg(dm, bot, "x")), cfg.allowlist).isEmpty())
    }

    @Test
    fun directMessageTriggersWithoutMention() = runBlocking {
        val h = harness()
        assertEquals("replied", h.p.handle(msg(dm, alice, "what time is it")))
        assertEquals(listOf("pong"), h.replies)
    }

    @Test
    fun groupStillNeedsMention() = runBlocking {
        val h = harness()
        assertEquals("skip: no trigger", h.p.handle(msg(group, alice, "hello all")))
        assertEquals("replied", h.p.handle(msg(group, alice, "@quagmire hello")))
        assertEquals("replied", h.p.handle(msg(group, bob, "@bot.example.com hello")))
    }

    @Test
    fun robotAndOwnMessagesNeverTrigger() = runBlocking {
        val h = harness()
        assertEquals("skip: no trigger", h.p.handle(msg(dm, alice, "🤖 pong")))
        assertEquals("skip: own message", h.p.handle(msg(dm, bot, "hello")))
        assertEquals("skip: own message", h.p.handle(msg(dm, bot, "@quagmire hello")))
        assertTrue(h.replies.isEmpty())
    }

    @Test
    fun ownerRestrictsAuthors() = runBlocking {
        val cfg = parseConfig("bot=true\nowner=alice.example.com", bot)
        cfg.allowlist.learnDerived(ConversationInfo(dm, "alice", listOf(bot, alice)))
        val bobDm = XorIdUtil.getNewXorId(bot.domainName, bob.domainName)
        cfg.allowlist.learnDerived(ConversationInfo(bobDm, "bob", listOf(bot, bob)))
        assertTrue(cfg.allowlist.allowsAuthor(alice, dm))
        assertFalse(cfg.allowlist.allowsAuthor(bob, bobDm))
    }

    @Test
    fun directTriggersAreRateLimited() = runBlocking {
        val h = harness(perHour = 2)
        assertEquals("replied", h.p.handle(msg(dm, alice, "1")))
        assertEquals("replied", h.p.handle(msg(dm, alice, "2")))
        assertEquals("skip: rate limited", h.p.handle(msg(dm, alice, "3")))
        assertEquals(2, h.replies.size)
    }

    @Test
    fun personaAndIdentityInPrompt() = runBlocking {
        val h = harness(persona = "You are Glen, a dry-witted assistant.")
        h.p.handle(msg(dm, alice, "hi"))
        h.p.handle(msg(group, alice, "@quagmire hi"))
        val direct = h.prompts[0]
        assertTrue(direct.startsWith("You are Glen, a dry-witted assistant.\nYou are bot.example.com, replying in a private chat."))
        assertTrue(direct.contains("members: alice.example.com"))
        assertTrue(h.prompts[1].startsWith("You are Glen, a dry-witted assistant.\nYou are bot.example.com, replying in a group conversation."))
        assertTrue(h.prompts[1].contains("title: Team"))
        assertNotNull(config("x y").persona)
        Unit
    }

    @Test
    fun personaFileIsRead() {
        val f = java.io.File.createTempFile("persona", ".txt").apply { deleteOnExit(); writeText("Line one.\nLine two.\n") }
        assertEquals("Line one. Line two.", parseConfig("personaFile=${f.absolutePath}", bot).persona)
    }
}
