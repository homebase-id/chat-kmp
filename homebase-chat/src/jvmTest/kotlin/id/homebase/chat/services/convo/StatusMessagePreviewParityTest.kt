package id.homebase.chat.services.convo

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.ConversationUiModel.Companion.updateWithLatestMessage
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * #1153 — the conversation-list row for a thread whose newest item is a
 * **status message** (`dataType = 202`: group rename, photo update, member
 * add/remove) must look the same however the row was populated.
 *
 * Two independent paths own the row's last-message fields (see the
 * "Message-preview ownership" section of [mergeConversationFileUpdate]):
 *
 *  - **live arrival** — [applyIncomingMessageBump], driven by a WS push.
 *  - **cold start / post-`DriveSync.Stopped` enrichment** —
 *    [ConversationMapper.mapToBasic] + [ConversationMapper.applyLastMessage],
 *    fed by `selectAllConversationPlusLastMessage`, which **excludes**
 *    `dataType = 202`.
 *
 * Before the fix the live path called `withPreviewOf()` unconditionally on
 * its strictly-newer branch and set `latestMessageTimestamp = sqlUserDate`,
 * so a status message set the preview to *"You updated the conversation
 * photo"* and floated the row to the top — while a restart re-derived the
 * same row from SQL and showed the newest **real** message instead, at that
 * message's timestamp. Same conversation, same data, two different rows.
 *
 * These tests exercise both paths as the pure functions they are (no DB, no
 * Koin, no coroutines) and assert the resulting rows are identical.
 */
class StatusMessagePreviewParityTest {

    private val me = OdinId("owner.test")
    private val alice = OdinId("alice.test")
    private val convoId = Uuid.parse("33333333-3333-3333-3333-333333333333")

    /** `fileMetadata.created` of the conversation file — mapToBasic's sort-key fallback. */
    private val conversationCreatedMs = 500L

    /** Newest real (`dataType = 0`) message: the only thing the SQL JOIN can return. */
    private val realMessageMs = 1_000L

    /** Group-photo update, authored by us, arriving after the real message. */
    private val statusMessageMs = 2_000L

    /**
     * What [ConversationMapper.mapToBasic] produces: identity + membership, the
     * `" "` last-message placeholder, `unreadCount = 0`, and the sort key seeded
     * from the persisted `localAppData.latestMessageTimestamp` — falling back to
     * `metadata.created` when the lastRead writeback has never flushed for this
     * thread (the common case for a thread the user hasn't opened).
     */
    private fun basicRow(sortKeyMs: Long = conversationCreatedMs) = ConversationUiModel(
        id = convoId,
        name = "the group",
        lastMessage = " ",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(sortKeyMs),
        unreadCount = 0,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = listOf(me, alice),
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
        admins = setOf(me),
        conversationState = ConversationState.Active,
        isGroup = true,
        fileCreated = Instant.fromEpochMilliseconds(conversationCreatedMs),
    )

    private fun message(
        author: OdinId,
        userDateMs: Long,
        content: String,
        isStatusMessage: Boolean = false,
    ) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = convoId,
        content = content,
        userDate = Instant.fromEpochMilliseconds(userDateMs),
        modified = null,
        created = Instant.fromEpochMilliseconds(userDateMs),
        originalAuthor = author,
        sender = author,
        displayName = author.domainName,
        localReadTimestamp = null as UnixTimeUtc?,
        isEdited = false,
        isDeleted = false,
        isPendingSend = false,
        isStatusMessage = isStatusMessage,
        versionTag = Uuid.NIL,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader.empty(),
        hasMore = false,
    )

    private val realMessage =
        message(author = alice, userDateMs = realMessageMs, content = "see you tomorrow")

    private val statusMessage = message(
        author = me,
        userDateMs = statusMessageMs,
        content = "You updated the conversation photo",
        isStatusMessage = true,
    )

    private fun bump(
        items: List<ConversationUiModel>,
        m: MessageUiModel,
        sqlUserDateMs: Long,
    ) = applyIncomingMessageBump(
        items = items,
        targetConversationId = convoId,
        m = m,
        sqlUserDate = Instant.fromEpochMilliseconds(sqlUserDateMs),
        activeDomain = me,
    )

    /**
     * LIVE — app in the foreground: the real message arrives over the WS, then
     * the group-photo status message arrives.
     */
    private fun liveRow(): ConversationUiModel {
        val afterReal = bump(listOf(basicRow()), realMessage, realMessageMs)
        assertNotNull(afterReal, "a real peer message must change the row")
        val afterStatus = bump(afterReal, statusMessage, statusMessageMs)
        return (afterStatus ?: afterReal).single()
    }

    /**
     * COLD START — same thread, same two files on disk, app restarted.
     *
     * `mapToBasic` seeds the placeholder row, `enrichWithLastMessages` runs
     * `selectAllConversationPlusLastMessage` (which can only ever hand back the
     * real message — `dataType != 202`) and patches it via `applyLastMessage`,
     * and `enrichAllConversationsWithUnreadCounts` recounts unread from
     * `selectAllUnreadCount`.
     *
     * @param sortKeyMs the persisted `localAppData.latestMessageTimestamp`, or
     *   `metadata.created` when the lastRead writeback never flushed.
     * @param unreadCount what `selectAllUnreadCount` returns. That query filters
     *   `dataType = 0`, so the status message contributes nothing — exactly
     *   matching the live path's `!m.isStatusMessage` increment gate. Only
     *   alice's real message counts.
     */
    private fun coldStartRow(
        sortKeyMs: Long = conversationCreatedMs,
        unreadCount: Int = 1,
    ): ConversationUiModel = basicRow(sortKeyMs)
        .updateWithLatestMessage(
            msg = realMessage,
            activeUserDomain = me,
            latestTimestampOverrideMs = realMessageMs,
        )
        .copy(unreadCount = unreadCount)

    /**
     * THE parity guard. Every field of the row — preview text, sender,
     * delivery/payload flags and the `latestMessageTimestamp` sort key — must
     * match between a live status-message arrival and a cold start over the
     * same two files.
     */
    @Test
    fun liveArrival_andColdStart_produceIdenticalRow_whenNewestItemIsAStatusMessage() {
        assertEquals(coldStartRow(), liveRow())
    }

    /**
     * Same guard for the thread the user *has* opened, so the lastRead
     * writeback has flushed `localAppData.latestMessageTimestamp` and
     * `mapToBasic` seeds from it instead of `metadata.created`. The seed can
     * only ever be the value the live path landed on, so pinning it here keeps
     * the two seeds honest.
     */
    @Test
    fun liveArrival_andColdStart_produceIdenticalRow_whenSortKeyWasPersisted() {
        val live = liveRow()
        val cold = coldStartRow(sortKeyMs = live.latestMessageTimestamp.toEpochMilliseconds())
        assertEquals(cold, live)
    }

    /**
     * The mechanism behind the parity: a status message is not a last message,
     * so it must leave the conversation row completely alone — no preview, no
     * sort-key advance (which would float the thread to the top of the list for
     * no visible reason and then drop it back down on the next restart), no
     * unread bump.
     */
    @Test
    fun statusMessage_leavesTheRowUntouched() {
        val withRealMessage = bump(listOf(basicRow()), realMessage, realMessageMs)
        assertNotNull(withRealMessage)

        assertNull(
            bump(withRealMessage, statusMessage, statusMessageMs),
            "a status message must not change the conversation row",
        )
    }

    /**
     * The `sqlUserDate == latestMessageTimestamp` re-emit branch has to be
     * gated too: a delivery-status or soft-delete re-push of a status message
     * carries the original `userDate`, and rewriting the preview from it would
     * reopen the same disagreement through the back door.
     */
    @Test
    fun statusMessageReEmit_atTheCurrentSortKey_leavesTheRowUntouched() {
        val items = listOf(basicRow(sortKeyMs = statusMessageMs))

        assertNull(
            bump(items, statusMessage, statusMessageMs),
            "a status-message re-emit must not change the conversation row",
        )
    }

    /**
     * Guard rail on the gate's scope: only `dataType = 202` is excluded. A
     * normal message still drives the preview and the sort key on the live
     * path, which is what `selectAllConversationPlusLastMessage` returns on the
     * cold path.
     */
    @Test
    fun realMessage_stillDrivesPreviewAndSortKey() {
        val updated = bump(listOf(basicRow()), realMessage, realMessageMs)

        assertNotNull(updated)
        val row = updated.single()
        assertEquals("see you tomorrow", row.lastMessage)
        assertEquals(realMessageMs, row.latestMessageTimestamp.toEpochMilliseconds())
        assertEquals(1, row.unreadCount)
    }
}
