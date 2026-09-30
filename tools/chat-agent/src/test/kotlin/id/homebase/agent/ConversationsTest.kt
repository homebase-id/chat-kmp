package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class ConversationsTest {
    private val me = OdinId("bot.example.com")
    private val alice = OdinId("alice.example.com")
    private val bob = OdinId("bob.example.com")

    @Test
    fun parsesGroupTitleAndMembers() {
        val id = Uuid.random()
        val c = parseConversation(id, """{"title":"Team","recipients":["alice.example.com","bob.example.com","bot.example.com"]}""", me)
        assertEquals("Team", c.title)
        assertEquals(listOf(alice, bob, me), c.members)
    }

    @Test
    fun oneToOneWithoutTitleUsesOtherMember() {
        val c = parseConversation(Uuid.random(), """{"recipients":["alice.example.com","bot.example.com"]}""", me)
        assertEquals("alice.example.com", c.title)
    }

    @Test
    fun noteToSelfTitle() {
        val c = parseConversation(ChatProtocol.ConversationWithYourselfId, """{"recipients":["bot.example.com"]}""", me)
        assertEquals(NOTE_TO_SELF_TITLE, c.title)
    }

    @Test
    fun botDefaultsToMemberMode() {
        val cfg = parseConfig("bot=true", me)
        assertTrue(cfg.allowlist.memberMode)
        assertTrue(cfg.allowlist.authorsAnyMember)
        val owned = parseConfig("bot=true\nowner=alice.example.com", me)
        assertFalse(owned.allowlist.authorsAnyMember)
        assertEquals(setOf(alice), owned.allowlist.authors)
    }

    @Test
    fun meDefaultStaysNoteToSelfOwnerOnly() {
        val cfg = parseConfig("", me)
        assertFalse(cfg.allowlist.memberMode)
        assertFalse(cfg.allowlist.authorsAnyMember)
        assertEquals(setOf(ChatProtocol.ConversationWithYourselfId), cfg.allowlist.allowedConversationIds())
    }

    @Test
    fun memberModeAllowsOnlyDiscovered() {
        val group = Uuid.random()
        val allow = parseConfig("bot=true", me).allowlist
        assertFalse(allow.allowsConversation(group))
        assertFailsWith<IllegalArgumentException> { allow.requireConversation(group) }
        allow.learn(listOf(ConversationInfo(group, "Team", listOf(alice, me))))
        assertTrue(allow.allowsConversation(group))
        assertFalse(allow.allowsConversation(Uuid.random()))
        assertTrue(allow.allowsAuthor(alice, group))
        assertFalse(allow.allowsAuthor(bob, group))
        assertFalse(allow.allowsAuthor(null, group))
        assertEquals("Team", allow.title(group))
    }

    @Test
    fun seenMessageLoggedOnce() = runBlocking {
        val logs = mutableListOf<String>()
        val processor = WatchProcessor(
            config = AgentConfig(allowlist = Allowlist.default(alice)),
            identity = alice.toString(),
            store = ProcessedStore(null),
            history = { emptyList() },
            brain = { BrainOutcome.Output("NO_REPLY") },
            reply = { _, _ -> },
            log = { logs += it },
        )
        val m = ChatMsg(Uuid.random(), ChatProtocol.ConversationWithYourselfId, alice, "hello", 1L)
        assertEquals("skip: no trigger", processor.handle(m))
        assertEquals("seen", processor.handle(m))
        assertEquals(1, logs.size)
    }

    private fun groupProcessor(bot: Boolean, replies: MutableList<Pair<Uuid, String>>, group: Uuid, brainRuns: IntArray): WatchProcessor {
        val allow = parseConfig(if (bot) "bot=true" else "allowConversations=member", me).allowlist.also {
            it.learn(listOf(ConversationInfo(group, "Team", listOf(alice, me))))
        }
        return WatchProcessor(
            config = AgentConfig(bot = bot, allowlist = allow),
            identity = me.toString(),
            store = ProcessedStore(null),
            history = { emptyList() },
            brain = { brainRuns[0]++; BrainOutcome.Output("pong") },
            reply = { c, t -> replies += c to t },
            log = {},
        )
    }

    @Test
    fun botRepliesIntoTriggeringGroup() = runBlocking {
        val group = Uuid.random()
        val replies = mutableListOf<Pair<Uuid, String>>()
        val runs = intArrayOf(0)
        val m = ChatMsg(Uuid.random(), group, alice, "@quagmire hi", 1L)
        assertEquals("replied", groupProcessor(true, replies, group, runs).handle(m))
        assertEquals(listOf(group to "$BOT_PREFIX pong"), replies)
    }

    @Test
    fun delegateNeverRepliesIntoGroupEvenIfConfigured() = runBlocking {
        val group = Uuid.random()
        val replies = mutableListOf<Pair<Uuid, String>>()
        val runs = intArrayOf(0)
        val m = ChatMsg(Uuid.random(), group, me, "@quagmire hi", 1L)
        assertEquals("skip: send not permitted in this conversation", groupProcessor(false, replies, group, runs).handle(m))
        assertEquals(0, runs[0])
        assertTrue(replies.isEmpty())
    }
}
