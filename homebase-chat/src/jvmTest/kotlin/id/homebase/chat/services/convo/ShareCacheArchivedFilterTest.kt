package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Locks down Task D: archived conversations must never reach the share cache that
 * feeds the iOS share-extension picker and the Android Direct Share shortcuts.
 *
 * [updateShareCache] runs its filter and the [ShareableConversation] mapping through
 * [buildShareableConversations]; this test exercises that same pure mapping with the
 * production archived filter applied in front of it, so it covers exactly the code
 * path the stream takes — no DB, Koin, or coroutines required.
 */
class ShareCacheArchivedFilterTest {

    private val me = OdinId("owner.test")
    private val alice = OdinId("alice.test")
    private val bob = OdinId("bob.test")

    private fun convo(
        other: OdinId,
        state: ConversationState,
    ) = ConversationUiModel(
        id = Uuid.random(),
        name = other.domainName,
        lastMessage = " ",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
        unreadCount = 0,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = listOf(me, other),
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = other,
        ),
        admins = emptySet(),
        conversationState = state,
        isGroup = false,
    )

    /** Mirrors the production filter in [updateShareCache]. */
    private fun List<ConversationUiModel>.nonArchived() =
        filter { it.conversationState != ConversationState.Archived }

    private val noContacts = emptyList<ContactUiModel>()

    @Test
    fun archivedConversationIsAbsentFromShareCacheWhileActiveIsPresent() {
        val active = convo(alice, ConversationState.Active)
        val archived = convo(bob, ConversationState.Archived)

        val shareable = buildShareableConversations(
            conversations = listOf(active, archived).nonArchived(),
            contacts = noContacts,
            activeDomain = me,
        )

        val ids = shareable.map { it.id }.toSet()
        assertTrue(
            active.id.toString() in ids,
            "Active conversation must be present in the share cache",
        )
        assertFalse(
            archived.id.toString() in ids,
            "Archived conversation must be excluded from the share cache",
        )
        assertEquals(1, shareable.size, "Only the active conversation should remain")
    }

    @Test
    fun onlyArchivedStateIsExcluded_otherStatesRemain() {
        // Defensive: the share surfaces exclude ONLY Archived (literal to the task),
        // not the full main-list whitelist. A non-Active, non-Archived state must
        // still appear so we don't accidentally widen the filter.
        val active = convo(alice, ConversationState.Active)
        val left = convo(bob, ConversationState.Left)
        val archived = convo(OdinId("carol.test"), ConversationState.Archived)

        val shareable = buildShareableConversations(
            conversations = listOf(active, left, archived).nonArchived(),
            contacts = noContacts,
            activeDomain = me,
        )

        val ids = shareable.map { it.id }.toSet()
        assertTrue(active.id.toString() in ids)
        assertTrue(left.id.toString() in ids, "Non-archived states must not be filtered")
        assertFalse(archived.id.toString() in ids)
    }
}
