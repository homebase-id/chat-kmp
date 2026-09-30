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

    @Test
    fun groupTriggerSkippedUntilL6() = runBlocking {
        val group = Uuid.random()
        val allow = parseConfig("bot=true", me).allowlist.also {
            it.learn(listOf(ConversationInfo(group, "Team", listOf(alice, me))))
        }
        var brainRuns = 0
        val processor = WatchProcessor(
            config = AgentConfig(bot = true, allowlist = allow),
            identity = me.toString(),
            store = ProcessedStore(null),
            history = { emptyList() },
            brain = { brainRuns++; BrainOutcome.Output("x") },
            reply = { _, _ -> },
            log = {},
        )
        val m = ChatMsg(Uuid.random(), group, alice, "@quagmire hi", 1L)
        assertEquals("skip: group reply not supported yet (L6)", processor.handle(m))
        assertEquals(0, brainRuns)
    }
}
