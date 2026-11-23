@file:OptIn(ExperimentalTestApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.Base64Bytes
import id.homebase.chat.conversationlist.toReplyPreview
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.viewonce.ViewOnceDescriptor
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ReplyGalleryItemTest {

    private fun thumb(tag: Int) = ThumbnailDescriptor(1, 1, "image/png", Base64Bytes(byteArrayOf(tag.toByte())))

    private fun image(i: Int) = PayloadDescriptor(key = "chat_web$i", contentType = "image/jpeg", previewThumbnail = thumb(i))

    private fun gallery(
        count: Int = 5,
        content: MessageContent? = null,
        text: String = "",
        deleted: Boolean = false,
    ) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = text,
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("alice.test"),
        sender = OdinId("alice.test"),
        displayName = "Alice",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = (listOf(PayloadDescriptor(key = ChatProtocol.DefaultPayloadKey)) + List(count) { image(it) }).toPersistentList(),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        messageContent = content,
        hasMore = false,
        isDeleted = deleted,
    )

    @Test
    fun theQuoteCarriesTheKeyAndThatItemsOwnThumbnail() {
        val preview = gallery().toReplyPreview("chat_web2")
        assertEquals("chat_web2", preview.payloadKey)
        assertEquals(2, preview.previewThumbnail?.content?.bytes?.single()?.toInt())
    }

    @Test
    fun noKeyOrAnUnknownKeyIsAWholeMessageQuote() {
        assertNull(gallery().toReplyPreview().payloadKey)
        assertNull(gallery().toReplyPreview("chat_web9").payloadKey)
    }

    @Test
    fun aSingleItemMessageNeverSendsAKey() {
        assertNull(gallery(count = 1).toReplyPreview("chat_web0").payloadKey)
    }

    @Test
    fun viewOnceMediaIsNeverQuotedByKey() {
        val viewOnce = gallery(content = MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE)))
        assertNull(viewOnce.toReplyPreview("chat_web1").payloadKey)
    }

    @Test
    fun theLookupIsPositionalWithinMediaOnly() {
        val item = gallery().quotedGalleryItem("chat_web2")!!
        assertEquals(3, item.position)
        assertEquals(5, item.total)
        assertEquals("chat_web2", item.payload.key)
        assertNull(gallery(deleted = true).quotedGalleryItem("chat_web2"))
        assertNull(gallery().quotedGalleryItem(ChatProtocol.DefaultPayloadKey))
    }

    @Test
    fun theTextKeepsTheCaption() {
        assertEquals("Photo 3 of 5", quotedItemText("Photo 3 of 5", ""))
        assertEquals("Photo 3 of 5 · hi", quotedItemText("Photo 3 of 5", "hi"))
    }

    @Test
    fun theInlineQuoteNamesTheItemAndFallsBackToTheWholeMessage() {
        val original = gallery(text = "hi")
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    InlineReplyPreview(replyPreview = original.toReplyPreview("chat_web2"), sentByYou = true, onClick = {}, replyMessage = original, driveId = Uuid.random())
                }
            }
            onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT, useUnmergedTree = true).assertTextEquals("Photo 3 of 5 · hi")
        }
        runComposeUiTest {
            val stale = original.toReplyPreview("chat_web2").copy(payloadKey = "chat_web9")
            setContent {
                MaterialTheme {
                    InlineReplyPreview(replyPreview = stale, sentByYou = true, onClick = {}, replyMessage = original, driveId = Uuid.random())
                }
            }
            onNodeWithTag(ChatBubbleTestTags.REPLY_QUOTE_TEXT, useUnmergedTree = true).assertTextEquals("hi")
        }
    }
}
