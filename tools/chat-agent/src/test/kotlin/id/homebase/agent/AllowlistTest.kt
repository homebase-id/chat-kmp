package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AllowlistTest {
    private val owner = OdinId("owner.example.com")
    private val allowlist = Allowlist.default(owner)

    @Test
    fun defaultAllowsOnlyNoteToSelf() {
        assertEquals(setOf(ChatProtocol.ConversationWithYourselfId), allowlist.conversationIds)
        assertTrue(allowlist.allowsConversation(ChatProtocol.ConversationWithYourselfId))
        assertFalse(allowlist.allowsConversation(Uuid.random()))
    }

    @Test
    fun defaultAllowsOnlyOwnerAsAuthor() {
        assertTrue(allowlist.allowsAuthor(OdinId("owner.example.com")))
        assertFalse(allowlist.allowsAuthor(OdinId("other.example.com")))
        assertFalse(allowlist.allowsAuthor(null))
    }

    @Test
    fun requireConversationRefusesOthers() {
        allowlist.requireConversation(ChatProtocol.ConversationWithYourselfId)
        assertFailsWith<IllegalArgumentException> { allowlist.requireConversation(Uuid.random()) }
    }
}
