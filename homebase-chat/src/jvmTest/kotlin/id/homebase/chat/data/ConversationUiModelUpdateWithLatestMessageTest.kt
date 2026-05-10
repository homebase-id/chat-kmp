package id.homebase.chat.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.ConversationUiModel.Companion.updateWithLatestMessage
import id.homebase.chat.services.MessageAppData
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Locks down the SQL-userDate override path on `updateWithLatestMessage`.
 *
 * The bug this guards: `mapToMessageData` (in `MessageMapper.kt`) clamps the
 * in-memory `MessageUiModel.userDate` to `min(appData.userDate, transitCreated)`.
 * When a peer's `appData.userDate` runs ahead of `transitCreated`, the
 * conversation's `latestMessageTimestamp` would freeze at the smaller
 * value while `selectAllUnreadCount` (filtering on the un-clamped SQL
 * column) keeps counting the row. Read-bookkeeping then can never reach
 * the SQL value and the badge gets stuck.
 *
 * The override parameter takes the SQL-faithful userDate from
 * `selectAllConversationPlusLastMessage.msgUserDate` (or
 * `HomebaseFile.sqlUserDateMs()` for fresh files), bypassing the clamp.
 */
class ConversationUiModelUpdateWithLatestMessageTest {

    private val me = OdinId("owner.test")
    private val alice = OdinId("alice.test")

    private fun convo(latestMs: Long) = ConversationUiModel(
        id = Uuid.random(),
        name = "alice",
        lastMessage = " ",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(latestMs),
        unreadCount = 0,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = listOf(me, alice),
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = alice,
        ),
        admins = emptySet(),
        conversationState = ConversationState.Active,
        isGroup = false,
    )

    /** Mimics what `mapToMessageData` returns for a peer message: userDate clamped to transitCreated. */
    private fun peerMessage(clampedUserDateMs: Long) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "hi",
        userDate = Instant.fromEpochMilliseconds(clampedUserDateMs),
        modified = null,
        created = Instant.fromEpochMilliseconds(clampedUserDateMs),
        originalAuthor = alice,
        sender = alice,
        displayName = "alice",
        localReadTimestamp = null as UnixTimeUtc?,
        isDeleted = false,
        isPendingSend = false,
        versionTag = Uuid.NIL,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader.empty(),
        hasMore = false,
    )

    @Test
    fun override_takesPrecedence_whenSqlUserDateExceedsClampedValue() {
        val convo = convo(latestMs = 1_000_000_000_000L)
        val msg = peerMessage(clampedUserDateMs = 1_776_843_935_755L)
        val sqlUserDateMs = 1_776_843_937_255L // 2 seconds ahead of clamped

        val result = convo.updateWithLatestMessage(
            msg = msg,
            activeUserDomain = me,
            latestTimestampOverrideMs = sqlUserDateMs,
        )

        assertEquals(sqlUserDateMs, result.latestMessageTimestamp.toEpochMilliseconds())
    }

    @Test
    fun override_null_fallsBackTo_clampedMessageUserDate() {
        val convo = convo(latestMs = 0L)
        val msg = peerMessage(clampedUserDateMs = 1_776_843_935_755L)

        val result = convo.updateWithLatestMessage(
            msg = msg,
            activeUserDomain = me,
            latestTimestampOverrideMs = null,
        )

        assertEquals(1_776_843_935_755L, result.latestMessageTimestamp.toEpochMilliseconds())
    }

    @Test
    fun override_doesNotRegress_whenAlreadyAhead() {
        val ahead = 2_000_000_000_000L
        val convo = convo(latestMs = ahead)
        val msg = peerMessage(clampedUserDateMs = 1_000L)

        val result = convo.updateWithLatestMessage(
            msg = msg,
            activeUserDomain = me,
            latestTimestampOverrideMs = 1_000L, // both candidate paths < latest
        )

        // Equality not required; the contract is "no regression".
        assertEquals(ahead, result.latestMessageTimestamp.toEpochMilliseconds())
    }
}
