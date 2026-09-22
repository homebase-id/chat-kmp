package id.homebase.chat.conversationlist

import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.resources.MR
import id.homebase.resources.chat_search_result_conversations
import id.homebase.resources.chat_search_result_pinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationListTopAnchorTest {

    private fun row(id: Uuid, atMs: Long = 0L) = ConversationListContentModel.Conversation(
        EnrichedConversationUiModel(
            conversation = ConversationUiModel(
                id = id,
                name = "convo",
                lastMessage = "",
                latestMessageTimestamp = Instant.fromEpochMilliseconds(atMs),
                avatarInitials = "",
                avatarTiny = null,
                lastRead = Instant.fromEpochMilliseconds(0),
                avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
                admins = emptySet(),
                participants = listOf(OdinId("frodo.demo.rocks")),
            ),
            participants = emptyList(),
            missingConnections = emptyList(),
        )
    )

    private val john = Uuid.random()
    private val jane = Uuid.random()

    @Test
    fun topUnchangedKeepsThePosition() {
        assertFalse(shouldScrollToTop(john, john, isAtTop = false))
    }

    @Test
    fun topChangedScrollsToTop() {
        assertTrue(shouldScrollToTop(john, jane, isAtTop = false))
    }

    @Test
    fun alreadyAtTopIsAlwaysANoOp() {
        assertFalse(shouldScrollToTop(john, jane, isAtTop = true))
        assertFalse(shouldScrollToTop(john, john, isAtTop = true))
    }

    @Test
    fun noSnapshotYetDoesNotJump() {
        assertFalse(shouldScrollToTop(null, jane, isAtTop = false))
    }

    @Test
    fun emptyListDoesNotJump() {
        assertFalse(shouldScrollToTop(john, null, isAtTop = false))
        assertNull(resolveTopConversationId(emptyList()))
    }

    @Test
    fun theTopIsTheMostRecentlyActiveConversationNotTheFirstListRow() {
        val items = listOf(
            ConversationListContentModel.Header(MR.string.chat_search_result_pinned),
            row(john, atMs = 10),
            row(jane, atMs = 20),
        )
        assertEquals(jane, resolveTopConversationId(items))
    }

    // A pinned row sits above everything and rarely changes; anchoring on row order would mean
    // an inbound message never registers as a reorder for anyone who has pinned a conversation.
    @Test
    fun aStalePinnedRowDoesNotMaskActivityBelowIt() {
        val pinned = Uuid.random()
        val before = listOf(
            ConversationListContentModel.Header(MR.string.chat_search_result_pinned),
            row(pinned, atMs = 5),
            ConversationListContentModel.Header(MR.string.chat_search_result_conversations),
            row(john, atMs = 20),
            row(jane, atMs = 10),
        )
        val after = listOf(
            ConversationListContentModel.Header(MR.string.chat_search_result_pinned),
            row(pinned, atMs = 5),
            ConversationListContentModel.Header(MR.string.chat_search_result_conversations),
            row(jane, atMs = 30),
            row(john, atMs = 20),
        )
        assertTrue(
            shouldScrollToTop(
                resolveTopConversationId(before),
                resolveTopConversationId(after),
                isAtTop = false,
            )
        )
    }

    @Test
    fun aHeaderOnlyListHasNoTopConversation() {
        val items = listOf(
            ConversationListContentModel.Header(MR.string.chat_search_result_conversations)
        )
        assertNull(resolveTopConversationId(items))
    }
}
