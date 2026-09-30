package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.ReplyPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpTest {
    private val owner = OdinId("owner.example.com")
    private val stranger = OdinId("other.example.com")
    private val self = ChatProtocol.ConversationWithYourselfId
    private val other = Uuid.random()

    private class FakeBackend(override val allowlist: Allowlist, val msgs: List<ChatMsg>) : AgentBackend {
        var lastLimit = -1
        var lastBefore: Long? = null
        val sent = mutableListOf<String>()
        var lastReply: ReplyPreview? = null
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?): List<ChatMsg> {
            lastLimit = limit
            lastBefore = beforeMs
            return msgs.filter { beforeMs == null || it.userDate < beforeMs }
        }
        override suspend fun send(conversationId: Uuid, text: String, replyTo: ReplyPreview?): Uuid {
            sent += text
            lastReply = replyTo
            return Uuid.random()
        }
    }

    private fun msg(author: OdinId?, text: String, date: Long = 1_790_000_000_000L) =
        ChatMsg(Uuid.random(), self, author, text, date)

    private fun backend(vararg msgs: ChatMsg) = FakeBackend(Allowlist.default(owner), msgs.toList())

    @Test
    fun listReturnsOnlyAllowlisted() = runBlocking {
        val reply = toolListConversations(backend())
        assertEquals("$self Note to self (1 members)", reply.text)
    }

    @Test
    fun readRefusesNonAllowlisted() = runBlocking {
        val b = backend()
        val reply = toolReadMessages(b, buildJsonObject { put("conversationId", other.toString()) })
        assertTrue(reply.isError)
        assertTrue(reply.text.startsWith("refused"))
        assertEquals(-1, b.lastLimit)
    }

    @Test
    fun readFormatsLinesAndFiltersAuthorsAndClampsLimit() = runBlocking {
        val b = backend(msg(owner, "hello\nworld"), msg(stranger, "spam"))
        val reply = toolReadMessages(b, buildJsonObject {
            put("conversationId", self.toString())
            put("limit", 500)
        })
        assertFalse(reply.isError)
        assertEquals(MCP_MAX_READ, b.lastLimit)
        val lines = reply.text.lines()
        assertEquals(1, lines.size)
        assertTrue(Regex("""[0-9a-f]{8} \d{4}-\d\d-\d\dT\d\d:\d\d owner\.example\.com: hello\\nworld""").matches(lines[0]), lines[0])
    }

    @Test
    fun sendRefusesNonAllowlistedAndPrefixesAllowed() = runBlocking {
        val b = backend()
        val refused = toolSendMessage(b, buildJsonObject {
            put("conversationId", other.toString())
            put("text", "hi")
        })
        assertTrue(refused.isError)
        assertTrue(b.sent.isEmpty())

        val ok = toolSendMessage(b, buildJsonObject {
            put("conversationId", self.toString())
            put("text", "hi")
        })
        assertFalse(ok.isError)
        assertEquals(listOf("$BOT_PREFIX hi"), b.sent)
    }

    @Test
    fun badInputsAreErrors() = runBlocking {
        val b = backend()
        assertTrue(toolReadMessages(b, null).isError)
        assertTrue(toolSendMessage(b, buildJsonObject { put("conversationId", self.toString()) }).isError)
        assertTrue(
            toolSendMessage(b, buildJsonObject {
                put("conversationId", "nope")
                put("text", "x")
            }).isError
        )
    }

    @Test
    fun readBeforeAcceptsDateAndIdPrefixAndOnlyReturnsOlder() = runBlocking {
        val old = msg(owner, "old", 1_000_000_000_000L)
        val mid = msg(owner, "mid", 1_500_000_000_000L)
        val new = msg(owner, "new", 1_790_000_000_000L)
        val b = backend(old, mid, new)
        val byId = toolReadMessages(b, buildJsonObject {
            put("conversationId", self.toString())
            put("before", mid.id.toString().take(8))
        })
        assertEquals(1_500_000_000_000L, b.lastBefore)
        assertEquals(listOf("old"), byId.text.lines().map { it.substringAfter(": ") })
        val byDate = toolReadMessages(b, buildJsonObject {
            put("conversationId", self.toString())
            put("before", "2026-01-01")
        })
        assertEquals(1_767_225_600_000L, b.lastBefore)
        assertEquals(listOf("old", "mid"), byDate.text.lines().map { it.substringAfter(": ") })
        assertTrue(toolReadMessages(b, buildJsonObject {
            put("conversationId", self.toString())
            put("before", "zzzzzzzz")
        }).isError)
    }

    @Test
    fun searchIsCaseInsensitiveLimitedAndAllowlisted() = runBlocking {
        val b = backend(msg(owner, "Hello World", 1L), msg(owner, "nothing", 2L), msg(stranger, "hello spam", 3L), msg(owner, "say HELLO", 4L))
        val reply = toolSearchMessages(b, buildJsonObject { put("query", "hello") })
        assertEquals(listOf("Hello World", "say HELLO"), reply.text.lines().map { it.substringAfter(": ") })
        assertTrue(reply.text.lines().all { it.startsWith(self.toString().take(8)) })
        val one = toolSearchMessages(b, buildJsonObject {
            put("query", "hello")
            put("conversationId", self.toString())
            put("limit", 1)
        })
        assertEquals(listOf("say HELLO"), one.text.lines().map { it.substringAfter(": ") })
        assertTrue(toolSearchMessages(b, buildJsonObject {
            put("query", "x")
            put("conversationId", other.toString())
        }).isError)
        assertEquals("(no matches)", toolSearchMessages(b, buildJsonObject { put("query", "zzz") }).text)
    }

    @Test
    fun getConversationShowsTitleMembersAndSendPermission() = runBlocking {
        val ok = toolGetConversation(backend(), buildJsonObject { put("conversationId", self.toString().take(8)) })
        assertEquals("$self Note to self (1 members)\nmembers: you\ncan send: yes", ok.text)

        val allowlist = Allowlist(setOf(self, other), setOf(owner))
        allowlist.learn(listOf(ConversationInfo(other, "Team", listOf(owner, stranger))))
        val group = toolGetConversation(FakeBackend(allowlist, emptyList()), buildJsonObject { put("conversationId", other.toString()) })
        assertEquals("$other Team (2 members)\nmembers: ${owner}, ${stranger}\ncan send: no", group.text)
        assertTrue(toolGetConversation(backend(), buildJsonObject { put("conversationId", other.toString()) }).isError)
    }

    @Test
    fun sendReplyToBuildsPreviewFromParent() = runBlocking {
        val parent = msg(owner, "  the original question that is quite long", 5L)
        val b = backend(parent)
        val ok = toolSendMessage(b, buildJsonObject {
            put("conversationId", self.toString())
            put("text", "answer")
            put("replyToId", parent.id.toString())
        })
        assertFalse(ok.isError)
        assertEquals(parent.id, b.lastReply?.replyUniqueId)
        assertEquals("owner.example.com", b.lastReply?.authorOdinId)
        assertEquals("the original question that is quite long", b.lastReply?.message)
        val missing = toolSendMessage(b, buildJsonObject {
            put("conversationId", self.toString())
            put("text", "x")
            put("replyToId", "deadbeef")
        })
        assertTrue(missing.isError)
        assertEquals(1, b.sent.size)
    }
}
