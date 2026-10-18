package id.homebase.chat.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.content.ActionPolicy
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.viewonce.ViewOnceDescriptor
import kotlin.test.Test
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MessageUiModelTest {

    private val self = OdinId("frodo.test")
    private val alice = OdinId("alice.test")
    private val carol = OdinId("carol.test")

    @Test
    fun isOneToOne_regularOneToOne_isTrue() {
        val msg = message(
            sender = alice,
            originalAuthor = alice,
            conversationId = XorIdUtil.getNewXorId(self.domainName, alice.domainName),
        )
        assertTrue(msg.isOneToOne(self))
    }

    @Test
    fun isOneToOne_forwardedOneToOne_usesSenderNotOriginalAuthor() {
        // Alice forwarded carol's content to self in the (self, alice) 1:1.
        // sender = alice (counterparty); originalAuthor = carol (content origin).
        val msg = message(
            sender = alice,
            originalAuthor = carol,
            conversationId = XorIdUtil.getNewXorId(self.domainName, alice.domainName),
        )
        assertTrue(msg.isOneToOne(self), "forwarded 1:1 must classify by sender, not originalAuthor")
    }

    @Test
    fun isOneToOne_groupRandomGroupId_isFalse() {
        val msg = message(
            sender = alice,
            originalAuthor = alice,
            conversationId = Uuid.random(),
        )
        assertFalse(msg.isOneToOne(self))
    }

    @Test
    fun isOneToOne_senderNull_isFalse() {
        val msg = message(
            sender = null,
            originalAuthor = alice,
            conversationId = XorIdUtil.getNewXorId(self.domainName, alice.domainName),
        )
        assertFalse(msg.isOneToOne(self))
    }

    private fun payload(sticker: Boolean) = PayloadDescriptor(
        key = "pld_key01",
        contentType = "image/png",
        descriptorContent = if (sticker) """{"isSticker":true}""" else null,
    )

    @Test
    fun soloStickerPayload_usesStickerPolicy() {
        val msg = message(alice, alice, Uuid.random(), content = "", payloads = listOf(payload(true)))
        assertEquals("pld_key01", msg.stickerPayloadKey)
        assertEquals(ActionPolicy.Sticker, msg.actionPolicy)
        assertFalse(ActionPolicy.Sticker.allowEdit || ActionPolicy.Sticker.allowCopy || ActionPolicy.Sticker.allowShare)
        assertTrue(ActionPolicy.Sticker.allowReply && ActionPolicy.Sticker.allowForward && ActionPolicy.Sticker.allowInlineReactions)
    }

    @Test
    fun plainImageOrCaptionedSticker_keepsStandardPolicy() {
        val plain = message(alice, alice, Uuid.random(), content = "", payloads = listOf(payload(false)))
        val captioned = message(alice, alice, Uuid.random(), content = "hi", payloads = listOf(payload(true)))
        assertEquals(ActionPolicy.Standard, plain.actionPolicy)
        assertEquals(ActionPolicy.Standard, captioned.actionPolicy)
    }

    @Test
    fun viewOnceWithStickerPayload_isNotASticker() {
        val msg = message(
            alice, alice, Uuid.random(), content = "", payloads = listOf(payload(true)),
            messageContent = MessageContent.ViewOnce(ViewOnceDescriptor(kind = ViewOnceDescriptor.KIND_IMAGE)),
        )
        assertEquals(null, msg.stickerPayloadKey)
        assertEquals(msg.messageContent?.actions, msg.actionPolicy)
    }

    private fun message(
        sender: OdinId?,
        originalAuthor: OdinId?,
        conversationId: Uuid,
        content: String = "x",
        payloads: List<PayloadDescriptor>? = null,
        messageContent: MessageContent? = null,
    ): MessageUiModel = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = conversationId,
        content = content,
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = originalAuthor,
        sender = sender,
        displayName = "",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = payloads?.toImmutableList(),
        keyHeader = KeyHeader(
            iv = ByteArray(16),
            aesKey = SecureByteArray(ByteArray(16)),
        ),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
        messageContent = messageContent,
    )
}
