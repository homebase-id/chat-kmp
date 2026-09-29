package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
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
        val sent = mutableListOf<String>()
        override suspend fun messages(conversationId: Uuid, limit: Int): List<ChatMsg> {
            lastLimit = limit
            return msgs
        }
        override suspend fun send(text: String): Uuid {
            sent += text
            return Uuid.random()
        }
    }

    private fun msg(author: OdinId?, text: String, date: Long = 1_790_000_000_000L) =
        ChatMsg(Uuid.random(), self, author, text, date)

    private fun backend(vararg msgs: ChatMsg) = FakeBackend(Allowlist.default(owner), msgs.toList())

    @Test
    fun listReturnsOnlyAllowlisted() = runBlocking {
        val reply = toolListConversations(backend())
        assertEquals("$self Note to self", reply.text)
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
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d owner\.example\.com: hello\\nworld""").matches(lines[0]), lines[0])
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
}
