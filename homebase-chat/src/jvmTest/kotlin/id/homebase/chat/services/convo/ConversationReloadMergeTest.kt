package id.homebase.chat.services.convo

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Locks down [mergeReloadedConversationRow] — the per-row reconcile of
 * [ConversationStream.loadBasicConversations], which replaces the whole in-memory list
 * with freshly mapped disk rows on every chat-drive `DriveEvent.Stopped(totalCount > 0)`.
 *
 * The regression this exists to guard against: on app open the reload wiped every resolved
 * snippet back to the `mapToBasic` `" "` placeholder, so populated rows rendered the
 * "No messages yet" fallback until `enrichWithLastMessages` re-ran its JOIN — measured at
 * 115-452ms on a Redmi Note 5 Pro.
 *
 * The most important test here is [reloadKeepsResolvedPreview].
 */
class ConversationReloadMergeTest {

    private val me = OdinId("owner.test")
    private val alice = OdinId("alice.test")
    private val convoId = Uuid.parse("11111111-1111-1111-1111-111111111111")

    private val videoPayload = PayloadDescriptor(key = "chat_web0", contentType = "video/mp4")

    /** A row as it lives in memory once the message pipeline has enriched it. */
    private fun enrichedInMemory(
        lastMessage: String = "hello there",
        latestMs: Long = 10_000L,
        lastRead: Long = 0L,
        dirty: Boolean = false,
        unreadCount: Int = 3,
        firstPayload: PayloadDescriptor? = null,
        state: ConversationState = ConversationState.Active,
    ) = ConversationUiModel(
        id = convoId,
        name = "alice",
        lastMessage = lastMessage,
        latestMessageTimestamp = Instant.fromEpochMilliseconds(latestMs),
        unreadCount = unreadCount,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = listOf(me, alice),
        lastRead = Instant.fromEpochMilliseconds(lastRead),
        dirty = dirty,
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = alice,
        ),
        lastMessageDeliveryStatus = 40,
        lastMessageIsPendingSend = false,
        lastMessageIsDeleted = false,
        lastMessageFirstPayload = firstPayload,
        lastMessageHasMultiplePayloads = false,
        lastMessageIsFromActiveUser = true,
        lastMessageSender = alice,
        admins = emptySet(),
        conversationState = state,
    )

    /**
     * A row straight out of `ConversationMapper.mapToBasic` — the shape
     * `loadBasicConversations` produces. No preview data of any kind: the mapper does one
     * plain SELECT and hardcodes `lastMessage = " "`, leaving every other `lastMessage*`
     * field on its default. `unreadCount` is always 0 (the disk row cannot know it).
     */
    private fun diskBasic(
        latestMs: Long = 10_000L,
        lastRead: Long = 0L,
        state: ConversationState = ConversationState.Active,
    ) = ConversationUiModel(
        id = convoId,
        name = "alice",
        lastMessage = " ",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(latestMs),
        unreadCount = 0,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = listOf(me, alice),
        lastRead = Instant.fromEpochMilliseconds(lastRead),
        dirty = false,
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = alice,
        ),
        admins = emptySet(),
        conversationState = state,
    )

    /**
     * THE regression guard. A post-sync reload maps the conversation file again and gets
     * the `" "` placeholder back. The reconcile must keep the preview the message pipeline
     * resolved, or the row renders "No messages yet" for the width of the enrich JOIN.
     */
    @Test
    fun reloadKeepsResolvedPreview() {
        val result = mergeReloadedConversationRow(
            disk = diskBasic(),
            prior = enrichedInMemory(lastMessage = "hello there"),
        )

        assertEquals("hello there", result.merged.lastMessage)
        assertTrue(
            result.merged.lastMessage.isNotBlank(),
            "a reloaded row must never fall back to the mapToBasic placeholder — " +
                "that is the 'No messages yet' flash",
        )
        assertEquals(40, result.merged.lastMessageDeliveryStatus)
        assertEquals(alice, result.merged.lastMessageSender)
        assertTrue(result.merged.lastMessageIsFromActiveUser)
    }

    /**
     * A media-only message has a blank `lastMessage` and renders from its payload icon
     * instead. Dropping the payload blanks the row just as visibly, so it is carried too.
     */
    @Test
    fun reloadKeepsPayloadOnlyPreview() {
        val result = mergeReloadedConversationRow(
            disk = diskBasic(),
            prior = enrichedInMemory(lastMessage = "", firstPayload = videoPayload),
        )

        assertEquals(videoPayload, result.merged.lastMessageFirstPayload)
    }

    @Test
    fun reloadKeepsUnreadCountAndLocalReadState() {
        val result = mergeReloadedConversationRow(
            disk = diskBasic(lastRead = 0L),
            prior = enrichedInMemory(unreadCount = 3, lastRead = 5_000L, dirty = true),
        )

        assertEquals(3, result.merged.unreadCount)
        assertEquals(Instant.fromEpochMilliseconds(5_000L), result.merged.lastRead)
        assertTrue(result.merged.dirty, "an un-flushed lastRead advance still owes a push")
        assertFalse(result.remoteLastReadAdvanced)
    }

    /** A peer device's read echo arrives on disk ahead of ours: adopt it and report it. */
    @Test
    fun remoteReadAdvanceIsAdoptedAndReported() {
        val result = mergeReloadedConversationRow(
            disk = diskBasic(lastRead = 9_000L),
            prior = enrichedInMemory(lastRead = 5_000L, dirty = true),
        )

        assertEquals(Instant.fromEpochMilliseconds(9_000L), result.merged.lastRead)
        assertFalse(result.merged.dirty, "the remote read caught up with ours; nothing to push")
        assertTrue(result.remoteLastReadAdvanced)
    }

    /** The sort key never regresses behind what the message pipeline resolved. */
    @Test
    fun sortKeyNeverRegresses() {
        val result = mergeReloadedConversationRow(
            disk = diskBasic(latestMs = 8_000L),
            prior = enrichedInMemory(latestMs = 10_000L),
        )

        assertEquals(Instant.fromEpochMilliseconds(10_000L), result.merged.latestMessageTimestamp)
    }

    @Test
    fun newerDiskSortKeyWins() {
        val result = mergeReloadedConversationRow(
            disk = diskBasic(latestMs = 12_000L),
            prior = enrichedInMemory(latestMs = 10_000L),
        )

        assertEquals(Instant.fromEpochMilliseconds(12_000L), result.merged.latestMessageTimestamp)
    }

    /** Cold start: no prior row to carry anything from, the disk row stands as mapped. */
    @Test
    fun coldStartPassesTheDiskRowThrough() {
        val disk = diskBasic()
        val result = mergeReloadedConversationRow(disk = disk, prior = null)

        assertEquals(disk, result.merged)
        assertFalse(result.remoteLastReadAdvanced)
    }

    /**
     * A conversation that became a Deleted placeholder is not the same thread any more,
     * and no enrich pass will correct a carried-over preview — so it carries none.
     */
    @Test
    fun deletedPlaceholderCarriesNoPreview() {
        val result = mergeReloadedConversationRow(
            disk = diskBasic(state = ConversationState.Deleted),
            prior = enrichedInMemory(lastMessage = "hello there", firstPayload = videoPayload),
        )

        assertEquals(" ", result.merged.lastMessage)
        assertNull(result.merged.lastMessageFirstPayload)
    }

    @Test
    fun invalidPlaceholderCarriesNoPreview() {
        val result = mergeReloadedConversationRow(
            disk = diskBasic(state = ConversationState.Invalid),
            prior = enrichedInMemory(lastMessage = "hello there"),
        )

        assertEquals(" ", result.merged.lastMessage)
    }
}
