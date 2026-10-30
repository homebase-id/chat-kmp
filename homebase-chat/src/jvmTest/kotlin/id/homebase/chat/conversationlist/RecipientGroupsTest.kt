package id.homebase.chat.conversationlist

import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RecipientGroupsTest {

    private val me = OdinId("me.test")

    private fun row(name: String, vararg others: String, id: Uuid = Uuid.random(), group: Boolean = false) = EnrichedConversationUiModel(
        conversation = ConversationUiModel(
            id = id,
            name = name,
            lastMessage = " ",
            latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
            avatarInitials = "",
            avatarTiny = null,
            participants = listOf(me) + others.map { OdinId(it) },
            lastRead = Instant.fromEpochMilliseconds(0),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.Connection, odinId = me),
            admins = emptySet(),
            conversationState = ConversationState.Active,
            isGroup = group,
        ),
        participants = emptyList(),
        missingConnections = emptyList(),
    )

    private fun contact(name: String, odinId: String) = ContactUiModel(id = Uuid.random(), odinId = OdinId(odinId), name = name, avatarInitials = "")

    @Test
    fun youThenFiveRecentChatsThenContactsThenGroupsByName() {
        val self = row("", id = ChatProtocol.ConversationWithYourselfId)
        val chats = List(6) { row("c$it.test", "c$it.test") }
        val zeta = row("Zeta", "a.test", "b.test", group = true)
        val alpha = row("alpha", "c.test", "d.test", group = true)
        val groups = recipientGroups(listOf(self) + chats + listOf(zeta, alpha), listOf(contact("Bob", "bob.test"), contact("ann", "ann.test")))

        assertEquals(listOf(RecipientType.You, RecipientType.Recents, RecipientType.Contacts, RecipientType.Groups), groups.map { it.recipientType })
        assertEquals(chats.take(5).map { it.conversation.id }, groups[1].recipients.map { (it as RecipientModel.Conversation).conversation.conversation.id })
        assertEquals(listOf("ann", "Bob"), groups[2].recipients.map { it.name })
        assertEquals(listOf("alpha", "Zeta"), groups[3].recipients.map { it.name })
    }

    @Test
    fun searchKeepsMatchesByNameOrIdentityAndDropsEmptyGroups() {
        val groups = recipientGroups(listOf(row("Book club", "a.test", "b.test", group = true)), listOf(contact("Frodo", "frodo.test"), contact("Sam", "gamgee.test")))

        assertEquals(groups, groups.matching("", "You"))
        assertEquals(listOf("Frodo"), groups.matching("fro", "You").flatMap { it.recipients }.map { it.name })
        assertEquals(listOf("Sam"), groups.matching("GAMGEE", "You").flatMap { it.recipients }.map { it.name })
        assertEquals(listOf(RecipientType.Groups), groups.matching("club", "You").map { it.recipientType })
        assertEquals(emptyList(), groups.matching("nobody", "You"))
    }
}
