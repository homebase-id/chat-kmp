@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.createconversation

import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.matchesConversationQuery
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * #910: existing groups are selectable from the New Conversation picker and searchable by group
 * name or a member's name, and the chat-overview search matches a group by participant name.
 */
class PickerGroupsTest {

    private fun contact(name: String, handle: String) =
        ContactUiModel(id = Uuid.random(), odinId = OdinId(handle), name = name, avatarInitials = "")

    private fun group(name: String, members: List<String>) = ConversationUiModel(
        id = Uuid.random(),
        name = name,
        lastMessage = "",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
        avatarInitials = "",
        avatarTiny = null,
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
        admins = emptySet(),
        participants = members.map { OdinId(it) },
        isGroup = true,
    )

    private fun groupRows(items: List<CreateConversationListItem>) =
        items.filterIsInstance<CreateConversationListItem.Groups>().singleOrNull()?.groups.orEmpty()

    // ---- picker: filterAndGroup group rows ----

    @Test
    fun idle_listsAllGroups() {
        val items = filterAndGroup(
            contacts = emptyList(),
            groupConversations = listOf(group("Fellowship", listOf("frodo.demo.rocks"))),
            query = "",
            self = null,
        )
        assertEquals(listOf("Fellowship"), groupRows(items).map { it.name })
    }

    @Test
    fun noGroups_omitsGroupsSection() {
        val items = filterAndGroup(
            contacts = emptyList(),
            groupConversations = emptyList(),
            query = "",
            self = null,
        )
        assertTrue(items.none { it is CreateConversationListItem.Groups })
    }

    @Test
    fun search_matchesGroupByName() {
        val items = filterAndGroup(
            contacts = emptyList(),
            groupConversations = listOf(
                group("Fellowship", listOf("frodo.demo.rocks")),
                group("Council", listOf("elrond.demo.rocks")),
            ),
            query = "fellow",
            self = null,
        )
        assertEquals(listOf("Fellowship"), groupRows(items).map { it.name })
    }

    @Test
    fun search_matchesGroupByMemberName_whenTitleDoesnt() {
        val items = filterAndGroup(
            contacts = listOf(contact("Frodo Baggins", "frodo.demo.rocks")),
            groupConversations = listOf(group("Second Breakfast", listOf("frodo.demo.rocks"))),
            query = "frodo",
            self = null,
        )
        assertEquals(listOf("Second Breakfast"), groupRows(items).map { it.name })
    }

    @Test
    fun search_noGroupMatch_omitsSection() {
        val items = filterAndGroup(
            contacts = emptyList(),
            groupConversations = listOf(group("Fellowship", listOf("frodo.demo.rocks"))),
            query = "mordor",
            self = null,
        )
        assertTrue(items.none { it is CreateConversationListItem.Groups })
    }

    // ---- overview search: matchesConversationQuery ----

    private fun enrichedGroup(name: String, memberNames: List<String>): EnrichedConversationUiModel =
        EnrichedConversationUiModel(
            conversation = group(name, memberNames.map { "${it.lowercase()}.demo.rocks" }),
            participants = memberNames.map { contact(it, "${it.lowercase()}.demo.rocks") },
            missingConnections = emptyList(),
        )

    @Test
    fun overview_matchesByGroupName() {
        assertTrue(enrichedGroup("Fellowship", listOf("Frodo")).matchesConversationQuery("fellow"))
    }

    @Test
    fun overview_matchesByMemberName_whenTitleDoesnt() {
        assertTrue(enrichedGroup("Second Breakfast", listOf("Frodo", "Sam")).matchesConversationQuery("sam"))
    }

    @Test
    fun overview_noMatch() {
        assertFalse(enrichedGroup("Fellowship", listOf("Frodo")).matchesConversationQuery("mordor"))
    }
}
