package id.homebase.chat.viewonce

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.ConversationUiModel.Companion.updateWithLatestMessage
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.convo.applyIncomingMessageBump
import id.homebase.chat.services.mapToMessageData
import id.homebase.chat.widget.ChatBubbleTestTags
import id.homebase.chat.widget.ContentLabel
import id.homebase.chat.widget.InlineReplyPreview
import id.homebase.chat.widget.messageContentLabel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ViewOnceListPreviewTest {

    private val now = Clock.System.now().toEpochMilliseconds()
    private val me = OdinId(VO_OWNER)

    private fun descriptorJson(kind: String, caption: String? = null): String {
        val captionPart = caption?.let { ",\\\"caption\\\":\\\"$it\\\"" }.orEmpty()
        return """{\"schemaVersion\":1,\"kind\":\"$kind\"$captionPart}"""
    }

    private fun conversation() = ConversationUiModel(
        id = Uuid.random(),
        name = "convo",
        lastMessage = "",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
        avatarInitials = "",
        avatarTiny = null,
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
        admins = emptySet(),
        participants = listOf(me),
    )

    private suspend fun lastMessageOf(message: MessageUiModel) =
        conversation().updateWithLatestMessage(message, activeUserDomain = me)

    private fun labelOf(conversation: ConversationUiModel): ContentLabel? {
        var label: ContentLabel? = null
        runComposeUiTest {
            setContent {
                label = messageContentLabel(
                    textContent = conversation.lastMessage,
                    isDeleted = conversation.lastMessageIsDeleted,
                    firstPayload = conversation.lastMessageFirstPayload,
                    hasMultiplePayloads = conversation.lastMessageHasMultiplePayloads,
                    messageContent = conversation.lastMessageContent,
                )
            }
            waitForIdle()
        }
        return label
    }

    private suspend fun unopened(kind: String = "image", caption: String? = null) = assertNotNull(
        mapToMessageData(
            viewOnceHeader(createdMs = now - DAY_MS, content = descriptorJson(kind, caption)),
            ownerCredentials(),
        ),
    )

    private suspend fun spentLocally(kind: String) = assertNotNull(
        mapToMessageData(
            viewOnceHeader(
                fileState = "deleted",
                createdMs = now - DAY_MS,
                updatedMs = now,
                content = viewOnceTombstoneContent(descriptorJson(kind, "gone").replace("\\\"", "\"")).replace("\"", "\\\""),
                payloadsJson = "null",
            ),
            ownerCredentials(),
        ),
    )

    @Test
    fun anUnopenedItemPreviewsAsThePhotoOrVideoWordOnly() = runTest {
        val photo = labelOf(lastMessageOf(unopened("image", caption = "secret caption")))
        val video = labelOf(lastMessageOf(unopened("video", caption = "secret caption")))

        assertEquals("Photo", photo?.text)
        assertEquals(ViewOnceIcon, photo?.icon)
        assertEquals("Video", video?.text)
        assertEquals(ViewOnceIcon, video?.icon)
    }

    @Test
    fun aSpentItemStillPreviewsAsThePhotoOrVideoNotAsDeleted() = runTest {
        val photo = lastMessageOf(spentLocally("image"))
        val video = lastMessageOf(spentLocally("video"))

        assertTrue(photo.lastMessageIsDeleted)
        assertEquals("Photo", labelOf(photo)?.text)
        assertEquals("Video", labelOf(video)?.text)
        assertEquals(ViewOnceOpenedIcon, labelOf(photo)?.icon)
    }

    @Test
    fun aServerTombstoneThatLostItsKindStillSaysMediaNotDeleted() = runTest {
        val tombstone = assertNotNull(
            mapToMessageData(serverTombstone(createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS), ownerCredentials()),
        )
        val label = labelOf(lastMessageOf(tombstone))

        assertEquals(MessageContent.UNPARSEABLE_VIEW_ONCE_LABEL, label?.text)
        assertEquals(ViewOnceOpenedIcon, label?.icon)
    }

    @Test
    fun theLocalTombstoneKeepsTheKindButNeverTheCaption() = runTest {
        val model = spentLocally("video")
        val descriptor = (model.messageContent as MessageContent.ViewOnce).descriptor

        assertEquals(ViewOnceDescriptor.KIND_VIDEO, descriptor?.kind)
        assertNull(descriptor?.caption)
        assertNull(model.payloads)
        assertNull(model.previewThumbnail)
    }

    @Test
    fun spentItemsAreSpentAndUnopenedOnesAreNot() = runTest {
        assertFalse(ViewOnceRules.isSpent(unopened(), now, me))
        assertTrue(ViewOnceRules.isSpent(spentLocally("image"), now, me))
    }

    private fun quote(message: MessageUiModel, expectedText: String, caption: String? = null) = runComposeUiTest {
        setContent {
            MaterialTheme {
                InlineReplyPreview(
                    replyPreview = ReplyPreview(
                        replyUniqueId = message.id,
                        authorOdinId = "alice.test",
                        message = "",
                    ),
                    sentByYou = false,
                    onClick = {},
                    replyMessage = message,
                    driveId = Uuid.random(),
                )
            }
        }
        onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT, useUnmergedTree = true).assertTextEquals(expectedText)
        if (caption != null) assertEquals(0, onAllNodesWithText(caption, substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aQuoteOfAViewOnceMessageReadsPhotoOrVideoWithoutTheCaption() = runTest {
        quote(unopened("image", caption = "secret caption"), "Photo", caption = "secret caption")
        quote(unopened("video", caption = "secret caption"), "Video", caption = "secret caption")
        quote(spentLocally("video"), "Video")
    }

    @Test
    fun theRowStaysPhotoOrVideoWhenTheOpenedItemArrivesAsAReEmitOrAServerTombstone() = runTest {
        val convo = conversation()
        val sent = unopened("image")
        val first = assertNotNull(
            applyIncomingMessageBump(listOf(convo), convo.id, sent, Instant.fromEpochMilliseconds(5), me),
        )
        assertEquals("Photo", labelOf(first.single())?.text)

        val spent = spentLocally("image")
        val afterOpen = assertNotNull(
            applyIncomingMessageBump(first, convo.id, spent, Instant.fromEpochMilliseconds(5), me),
        ).single()
        assertTrue(afterOpen.lastMessageIsDeleted)
        assertEquals("Photo", labelOf(afterOpen)?.text)
        assertEquals(ViewOnceOpenedIcon, labelOf(afterOpen)?.icon)

        val tombstone = assertNotNull(
            mapToMessageData(serverTombstone(createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS), ownerCredentials()),
        )
        val afterSync = assertNotNull(
            applyIncomingMessageBump(listOf(afterOpen), convo.id, tombstone, Instant.fromEpochMilliseconds(5), me),
        ).single()
        assertNotEquals("This message was deleted", labelOf(afterSync)?.text)
        assertEquals(ViewOnceOpenedIcon, labelOf(afterSync)?.icon)
    }
}
