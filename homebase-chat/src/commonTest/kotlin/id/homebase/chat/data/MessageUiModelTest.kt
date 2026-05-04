package id.homebase.chat.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.XorIdUtil
import kotlin.test.Test
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

    private fun message(
        sender: OdinId?,
        originalAuthor: OdinId?,
        conversationId: Uuid,
    ): MessageUiModel = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = conversationId,
        content = "x",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = originalAuthor,
        sender = sender,
        displayName = "",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader(
            iv = ByteArray(16),
            aesKey = SecureByteArray(ByteArray(16)),
        ),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )
}
