package id.homebase.chat.services.convo

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pure unit tests for [ConversationEnricher]. No DB, no Koin, no coroutines.
 *
 * Locks down two contracts introduced by the mandatory/optional split:
 *   - [ConversationEnricher.enrich] tolerates `ownerSession = null`
 *     and renders best-effort output instead of throwing.
 *   - The `isWithSelf` branch always returns empty participants regardless
 *     of ownerSession state.
 */
class ConversationEnricherTest {

    private val me = OdinId("owner.test")
    private val alice = OdinId("alice.test")

    private val session = OwnerSession(
        odinId = me,
        displayName = "Owner",
        firstName = null,
        surName = null,
        profileImageFileId = null,
        profileImageFileKey = null,
        profileImagePreviewThumbnail = null,
        profileImageLastModified = null,
        status = null,
    )

    private fun oneOnOneConvo() = ConversationUiModel(
        id = Uuid.random(),
        name = "alice.test",
        lastMessage = " ",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
        unreadCount = 0,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = listOf(me, alice),
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = alice
        ),
        admins = emptySet(),
        conversationState = ConversationState.Active,
        isGroup = false,
    )

    // T1: we weakened the ownerSession gate in ConversationListViewModel so that
    // cold-load can render before the owner session resolves. The enricher is the
    // one that must not throw on a null session — this test locks that in.
    @Test
    fun enrich_nullOwnerSession_rendersBestEffort() {
        val enricher = ConversationEnricher()

        val result = enricher.enrich(
            convo = oneOnOneConvo(),
            contactMap = emptyMap(),
            ownerSession = null,
        )

        // Participants pass through unfiltered when we can't identify "self".
        // The enricher documents this as the intentional degradation path.
        assertEquals(2, result.conversation.participants.size)
        // No contacts were provided, so the resolved participants list is empty —
        // the UI layer falls back to odinId.domainName at render time.
        assertTrue(result.participants.isEmpty())
    }

    @Test
    fun enrich_withSelf_returnsEmptyParticipants() {
        val enricher = ConversationEnricher()

        val selfConvo = oneOnOneConvo().copy(
            id = ChatProtocol.ConversationWithYourselfId,
            participants = listOf(me),
        )

        val result = enricher.enrich(
            convo = selfConvo,
            contactMap = emptyMap(),
            ownerSession = session,
        )

        assertTrue(result.participants.isEmpty())
        assertTrue(result.missingConnections.isEmpty())
        assertEquals(null, result.oneOnOneConnectionStatus)
    }

    // Also verify self-branch doesn't crash without a session (edge case on top
    // of T1 — the null-session path and the isWithSelf path run independently).
    @Test
    fun enrich_withSelf_nullOwnerSession_doesNotCrash() {
        val enricher = ConversationEnricher()

        val selfConvo = oneOnOneConvo().copy(
            id = ChatProtocol.ConversationWithYourselfId,
            participants = listOf(me),
        )

        val result = enricher.enrich(
            convo = selfConvo,
            contactMap = emptyMap(),
            ownerSession = null,
        )

        assertTrue(result.participants.isEmpty())
    }
}
