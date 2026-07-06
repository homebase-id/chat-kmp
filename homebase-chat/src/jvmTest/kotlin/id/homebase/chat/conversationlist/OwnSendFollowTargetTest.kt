package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Gate for the one-time own-send follow (#995): a plain-text/reply/location send
 * arms [MessageListUiState.scrollToLatestRequest] but inserts its message
 * asynchronously (no placeholder), so [resolveOwnSendFollowTarget] must hold the
 * scroll back until the requested id is actually in the merged data AND the
 * LazyColumn has laid out at least that many items — scrolling earlier targets
 * the previous last item and leaves the send below the fold.
 */
class OwnSendFollowTargetTest {

    private val alice = OdinId("alice.test")
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val convoId = Uuid.random()

    private fun message(id: Uuid = Uuid.random()): MessageListContentModel.Message =
        MessageListContentModel.Message(
            message = MessageUiModel(
                id = id,
                globalTransitId = null,
                fileId = Uuid.random(),
                conversationId = convoId,
                content = "test",
                userDate = baseTime,
                modified = null,
                created = baseTime,
                originalAuthor = alice,
                sender = alice,
                displayName = alice.domainName,
                localReadTimestamp = null,
                isDeleted = false,
                isPendingSend = false,
                versionTag = Uuid.NIL,
                messageAppData = MessageAppData(),
                reactionPreview = null,
                previewThumbnail = null,
                payloads = null,
                keyHeader = KeyHeader.empty(),
                hasMore = false,
            ),
        )

    private fun pending(
        id: Uuid = Uuid.random(),
        conversationId: Uuid = convoId,
    ) = PendingOutgoingMessage(
        id = id,
        conversationId = conversationId,
        text = "pending",
        attachmentCount = 0,
        sentAt = baseTime,
    )

    @Test
    fun notInListYet_returnsNull() {
        // Armed at send time, but the optimistic round-trip hasn't landed the
        // message yet — must NOT scroll (that's the pre-fix race: it would
        // target the previous last item).
        val list = listOf<MessageListContentModel>(MessageListContentModel.Header, message())
        assertNull(
            resolveOwnSendFollowTarget(
                requestedId = Uuid.random(),
                messages = list,
                pendingOutgoing = emptyList(),
                conversationId = convoId,
                laidOutItemCount = list.size,
            )
        )
    }

    @Test
    fun presentAndLaidOut_returnsCount() {
        val sent = message()
        val list = listOf<MessageListContentModel>(
            MessageListContentModel.Header, message(), sent,
        )
        assertEquals(
            3,
            resolveOwnSendFollowTarget(
                requestedId = sent.message.id,
                messages = list,
                pendingOutgoing = emptyList(),
                conversationId = convoId,
                laidOutItemCount = 3,
            )
        )
    }

    @Test
    fun presentInDataButLayoutStale_returnsNull() {
        // The message reached uiState.messages but the LazyColumn hasn't
        // re-measured yet — scrolling now would land on the old last item.
        val sent = message()
        val list = listOf<MessageListContentModel>(
            MessageListContentModel.Header, message(), sent,
        )
        assertNull(
            resolveOwnSendFollowTarget(
                requestedId = sent.message.id,
                messages = list,
                pendingOutgoing = emptyList(),
                conversationId = convoId,
                laidOutItemCount = 2,
            )
        )
    }

    @Test
    fun presentAsPendingPlaceholder_returnsCount() {
        // Attachment path: the placeholder is registered in the same update
        // that arms the token, so the follow fires as soon as layout catches up.
        val placeholder = pending()
        val list = listOf<MessageListContentModel>(MessageListContentModel.Header, message())
        assertEquals(
            3,
            resolveOwnSendFollowTarget(
                requestedId = placeholder.id,
                messages = list,
                pendingOutgoing = listOf(placeholder),
                conversationId = convoId,
                laidOutItemCount = 3,
            )
        )
    }

    @Test
    fun placeholderSupersededByRealMessage_notDoubleCounted() {
        // mergedItems drops a placeholder once its real message lands; the
        // expected count must do the same or layout could never "catch up".
        val sent = message()
        val list = listOf<MessageListContentModel>(MessageListContentModel.Header, sent)
        assertEquals(
            2,
            resolveOwnSendFollowTarget(
                requestedId = sent.message.id,
                messages = list,
                pendingOutgoing = listOf(pending(id = sent.message.id)),
                conversationId = convoId,
                laidOutItemCount = 2,
            )
        )
    }

    @Test
    fun pendingFromOtherConversation_ignored() {
        val other = pending(conversationId = Uuid.random())
        val list = listOf<MessageListContentModel>(MessageListContentModel.Header, message())
        assertNull(
            resolveOwnSendFollowTarget(
                requestedId = other.id,
                messages = list,
                pendingOutgoing = listOf(other),
                conversationId = convoId,
                laidOutItemCount = 2,
            )
        )
    }

    @Test
    fun extraNonMergedRows_followsToRealEnd() {
        // The LazyColumn can hold rows beyond mergedItems (the empty-chat info
        // row); the target is always the laid-out end, not the merged count.
        val sent = message()
        val list = listOf<MessageListContentModel>(MessageListContentModel.Header, sent)
        assertEquals(
            3,
            resolveOwnSendFollowTarget(
                requestedId = sent.message.id,
                messages = list,
                pendingOutgoing = emptyList(),
                conversationId = convoId,
                laidOutItemCount = 3,
            )
        )
    }
}
