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
 * Locks down the `isWithSelf` invariant: the self-branch short-circuits
 * to empty participants before any contact/session data is consulted.
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
}
