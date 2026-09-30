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
}
