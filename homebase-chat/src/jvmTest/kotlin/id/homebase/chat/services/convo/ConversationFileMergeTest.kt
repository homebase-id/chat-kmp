package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
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
 * Locks down [mergeConversationFileUpdate] — the conversation-FILE refresh merge
 * extracted out of [ConversationStream.updateConversation].
 *
 * The regression this exists to guard against: sending a message blanked the
 * conversation's `lastMessage` preview to a single space.
 *
 * A conversation file never carries real preview data — it is mapped via
 * `mapToConversationUi(file, lastMsg = null)`, so `mapToBasic` hardcodes
 * `lastMessage = " "`. The pre-fix merge copied the preview fields from `incoming`
 * under an `incoming.latestMessageTimestamp >= existing.latestMessageTimestamp`
 * guard. Once `mapToBasic` started seeding `latestMessageTimestamp` from the
 * persisted localAppData sort key (commit 2e0d4e76, stamped on every send/read),
 * the post-send conv-file echo arrives with a timestamp EQUAL to `existing`, the
 * `>=` guard flips true, and the `" "` placeholder overwrites the real preview.
 *
 * The most important test here is [postSendEcho_doesNotBlankPreview].
 */
class ConversationFileMergeTest {

    private val me = OdinId("owner.test")
    private val alice = OdinId("alice.test")
    private val bob = OdinId("bob.test")
    private val convoId = Uuid.parse("11111111-1111-1111-1111-111111111111")

    private val now = Instant.fromEpochMilliseconds(9_999_999L)

    /** A conversation as it lives in memory (real preview from the message pipeline). */
    private fun existing(
        lastMessage: String = "hello there",
        latestMs: Long = 10_000L,
        lastRead: Long = 0L,
        dirty: Boolean = false,
        name: String = "alice",
        isPinned: Boolean = false,
        participants: List<OdinId> = listOf(me, alice),
        state: ConversationState = ConversationState.Active,
        isGroup: Boolean = false,
    ) = ConversationUiModel(
        id = convoId,
        name = name,
        lastMessage = lastMessage,
        latestMessageTimestamp = Instant.fromEpochMilliseconds(latestMs),
        unreadCount = 0,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = participants,
        isPinned = isPinned,
        lastRead = Instant.fromEpochMilliseconds(lastRead),
        dirty = dirty,
        lastMessageIsFromActiveUser = true,
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = alice,
        ),
        admins = emptySet(),
        conversationState = state,
        isGroup = isGroup,
    )

    /**
     * A conversation FILE refresh as produced by `mapToConversationUi(file, null)`:
     * the preview is always the `" "` placeholder, the sort timestamp comes from
     * persisted localAppData.
     */
    private fun incomingFile(
        latestMs: Long,
        lastRead: Long = 0L,
        name: String = "alice",
        isPinned: Boolean = false,
        participants: List<OdinId> = listOf(me, alice),
        state: ConversationState = ConversationState.Active,
        isGroup: Boolean = false,
    ) = ConversationUiModel(
        id = convoId,
        name = name,
        lastMessage = " ",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(latestMs),
        unreadCount = 0,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = participants,
        isPinned = isPinned,
        lastRead = Instant.fromEpochMilliseconds(lastRead),
        dirty = false,
        lastMessageIsFromActiveUser = false,
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = alice,
        ),
        admins = emptySet(),
        conversationState = state,
        isGroup = isGroup,
    )

    /**
     * THE regression guard. After sending a message the in-memory model holds
     * the real preview ("hello there") at timestamp T; the lastRead writeback
     * echoes the conversation file back stamped at the SAME T with the `" "`
     * placeholder preview. The merge must keep the real preview.
     */
    @Test
    fun postSendEcho_doesNotBlankPreview() {
        val result = mergeConversationFileUpdate(
            existing = existing(lastMessage = "hello there", latestMs = 10_000L),
            incoming = incomingFile(latestMs = 10_000L),
            now = now,
        )

        assertEquals(
            "hello there",
            result.merged.lastMessage,
            "post-send conv-file echo (same timestamp, placeholder preview) must not blank lastMessage",
        )
        assertEquals(10_000L, result.merged.latestMessageTimestamp.toEpochMilliseconds())
        assertTrue(result.merged.lastMessageIsFromActiveUser)
    }

    /**
     * A peer's conversation file can arrive (with its persisted sort key already
     * advanced) BEFORE the message that advanced it. The placeholder must not
     * blank the preview, and the sort timestamp must stay on `existing` so the
     * subsequent [applyIncomingMessageBump] (which requires
     * `sqlUserDate > existing.latestMessageTimestamp`) can still fire.
     */
    @Test
    fun peerFileBeforeMessage_doesNotBlankPreviewAndDoesNotAdvanceTimestamp() {
        val result = mergeConversationFileUpdate(
            existing = existing(lastMessage = "hello there", latestMs = 10_000L),
            incoming = incomingFile(latestMs = 15_000L),
            now = now,
        )

        assertEquals("hello there", result.merged.lastMessage)
        assertEquals(
            10_000L,
            result.merged.latestMessageTimestamp.toEpochMilliseconds(),
            "a strictly-newer placeholder file must not advance the sort key past the message pipeline",
        )
    }

    /** Structural fields still merge from the file; only the preview is frozen. */
    @Test
    fun structuralFieldsStillMergeFromFile() {
        val result = mergeConversationFileUpdate(
            existing = existing(name = "old name", isPinned = false, participants = listOf(me, alice)),
            incoming = incomingFile(
                latestMs = 10_000L,
                name = "new name",
                isPinned = true,
                participants = listOf(me, alice, bob),
            ),
            now = now,
        )

        assertEquals("new name", result.merged.name)
        assertTrue(result.merged.isPinned)
        assertEquals(listOf(me, alice, bob), result.merged.participants)
        // ...while the preview stays from existing.
        assertEquals("hello there", result.merged.lastMessage)
    }

    /** lastRead reconciliation is preserved by the extraction. */
    @Test
    fun lastReadReconciliation_takesMaxAndClearsDirtyWhenRemoteCatchesUp() {
        val result = mergeConversationFileUpdate(
            existing = existing(lastRead = 5_000L, dirty = true),
            incoming = incomingFile(latestMs = 10_000L, lastRead = 10_000L),
            now = now,
        )

        assertEquals(10_000L, result.merged.lastRead.toEpochMilliseconds())
        assertFalse(result.merged.dirty, "dirty clears once the remote lastRead reaches our local value")
    }

    /** Left is sticky against an outbox echo that omits the Left tag. */
    @Test
    fun leftState_isStickyAgainstActiveEcho() {
        val result = mergeConversationFileUpdate(
            existing = existing(state = ConversationState.Left, isGroup = true),
            incoming = incomingFile(latestMs = 10_000L, state = ConversationState.Active, isGroup = true),
            now = now,
        )

        assertEquals(ConversationState.Left, result.merged.conversationState)
    }

    /** First Removed transition reports isNewlyRemoved and stamps exitedAt = now. */
    @Test
    fun newlyRemoved_isReportedAndStampsExitedAt() {
        val result = mergeConversationFileUpdate(
            existing = existing(state = ConversationState.Active, isGroup = true),
            incoming = incomingFile(
                latestMs = 10_000L,
                state = ConversationState.Removed,
                isGroup = true,
                participants = listOf(alice, bob), // we are no longer a participant
            ),
            now = now,
        )

        assertTrue(result.isNewlyRemoved)
        assertEquals(ConversationState.Removed, result.merged.conversationState)
        assertEquals(now, result.merged.exitedAt)
    }
}
