package id.homebase.chat.services.convo

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The share-picker search seam (#984): searching your own name/handle surfaces the self
 * conversation (whose display name is the literal "You" label) via [matchesShareQuery],
 * while non-self rows keep matching on display name only.
 */
class ShareSelfMatchTest {

    private val me = OdinId("samwise.gamgee.demo.rocks")
    private val alice = OdinId("alice.test")

    private val session = OwnerSession(
        odinId = me,
        displayName = "Samwise Gamgee",
        firstName = null,
        surName = null,
        profileImageFileId = null,
        profileImageFileKey = null,
        profileImagePreviewThumbnail = null,
        profileImageLastModified = null,
        status = null,
    )

    private fun convo(id: Uuid, name: String, participants: List<OdinId>) = ConversationUiModel(
        id = id,
        name = name,
        lastMessage = " ",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
        avatarInitials = "",
        avatarTiny = null,
        participants = participants,
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = alice,
        ),
        admins = emptySet(),
        conversationState = ConversationState.Active,
    )

    private fun selfRow() = EnrichedConversationUiModel(
        conversation = convo(ChatProtocol.ConversationWithYourselfId, "", listOf(me)),
        participants = emptyList(),
        missingConnections = emptyList(),
    )

    private fun aliceRow() = EnrichedConversationUiModel(
        conversation = convo(Uuid.random(), "alice.test", listOf(me, alice)),
        participants = listOf(
            ContactUiModel(id = Uuid.random(), odinId = alice, name = "Alice", avatarInitials = ""),
        ),
        missingConnections = emptyList(),
    )

    @Test
    fun selfRow_matchesOwnerName() {
        assertTrue(selfRow().matchesShareQuery("samwise", session))
    }

    @Test
    fun selfRow_matchesOwnerHandle() {
        assertTrue(selfRow().matchesShareQuery("gamgee.demo", session))
    }

    @Test
    fun selfRow_stillMatchesYouLabel() {
        assertTrue(selfRow().matchesShareQuery("you", session))
    }

    @Test
    fun selfRow_noMatch_whenQueryHitsNothing() {
        assertFalse(selfRow().matchesShareQuery("mordor", session))
    }

    @Test
    fun selfRow_withoutSession_matchesOnlyYouLabel() {
        assertFalse(selfRow().matchesShareQuery("samwise", null))
        assertTrue(selfRow().matchesShareQuery("you", null))
    }

    @Test
    fun nonSelfRow_neverMatchesOwnerName() {
        assertFalse(aliceRow().matchesShareQuery("samwise", session))
    }

    @Test
    fun nonSelfRow_matchesDisplayName() {
        assertTrue(aliceRow().matchesShareQuery("alice", session))
    }
}
