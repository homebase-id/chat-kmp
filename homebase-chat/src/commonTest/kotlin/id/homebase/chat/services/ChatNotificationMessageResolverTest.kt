package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.content.MessageContent
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * #859: the bridge from the push-notification layer to the chat message pipeline. Verifies the
 * preview/sender are surfaced when a message resolves, and that any unresolved/blank case yields
 * null so the caller falls back to the generic body.
 */
class ChatNotificationMessageResolverTest {

    private fun message(
        content: String,
        displayName: String,
        payloads: List<PayloadDescriptor>? = null,
        messageContent: MessageContent? = null,
    ): MessageUiModel = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = content,
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = null,
        sender = null,
        displayName = displayName,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = payloads?.toImmutableList(),
        messageContent = messageContent,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    /** Fake that returns a canned resolution; other MessageLookup members are unused here. */
    private class FakeLookup(private val result: MessageUiModel?) : MessageLookup {
        override suspend fun getMessage(messageId: Uuid): MessageUiModel? = result
        override suspend fun getMessages(messageIds: List<Uuid>, conversationId: Uuid): BatchResult<MessageUiModel> =
            error("unused")
        override suspend fun getMessageFile(messageId: Uuid): HomebaseFile? = null
        override suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? = null
        override fun findCachedFileId(messageId: Uuid): Uuid? = null
        override suspend fun resolveForNotification(conversationId: Uuid, messageId: Uuid) = result
    }

    private fun resolver(result: MessageUiModel?) = ChatNotificationMessageResolver(FakeLookup(result))

    private val photo = PayloadDescriptor(key = "chat_img1", contentType = "image/webp", iv = Base64Bytes(ByteArray(16)))
    private val thumb = byteArrayOf(7, 7, 7)

    private fun conversation(isGroup: Boolean, name: String) = ConversationUiModel(
        id = Uuid.random(),
        name = name,
        lastMessage = "",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
        avatarInitials = "",
        avatarTiny = null,
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
        admins = emptySet(),
        isGroup = isGroup,
    )

    @Test
    fun resolvedMessage_yieldsPreviewAndSender() = runTest {
        val preview = resolver(message("Hey there", "Alice"))
            .resolvePreview(Uuid.random(), Uuid.random())
        assertEquals("Hey there", preview?.preview)
        assertEquals("Alice", preview?.senderName)
    }

    @Test
    fun unresolvedMessage_yieldsNull() = runTest {
        assertNull(resolver(null).resolvePreview(Uuid.random(), Uuid.random()))
    }

    @Test
    fun blankContent_yieldsNull() = runTest {
        assertNull(resolver(message("   ", "Alice")).resolvePreview(Uuid.random(), Uuid.random()))
    }

    @Test
    fun blankDisplayName_yieldsNullSender_butKeepsPreview() = runTest {
        val preview = resolver(message("Hi", "")).resolvePreview(Uuid.random(), Uuid.random())
        assertEquals("Hi", preview?.preview)
        assertNull(preview?.senderName)
    }

    @Test
    fun photoOnlyMessage_yieldsItsLabelAndThumbnail() = runTest {
        val loaded = mutableListOf<String>()
        val preview = ChatNotificationMessageResolver(
            FakeLookup(message("", "Alice", payloads = listOf(photo))),
            loadImage = { loaded += it.payloadKey; thumb },
        ).resolvePreview(Uuid.random(), Uuid.random())
        assertEquals("Image", preview?.preview)
        assertContentEquals(thumb, preview?.imageBytes)
        assertEquals(listOf("chat_img1"), loaded)
    }

    @Test
    fun captionedPhoto_keepsTheCaptionAndTheThumbnail() = runTest {
        val preview = ChatNotificationMessageResolver(
            FakeLookup(message("Look", "Alice", payloads = listOf(photo))),
            loadImage = { thumb },
        ).resolvePreview(Uuid.random(), Uuid.random())
        assertEquals("Look", preview?.preview)
        assertContentEquals(thumb, preview?.imageBytes)
    }

    @Test
    fun viewOnceMedia_neverLoadsTheImage() = runTest {
        val preview = ChatNotificationMessageResolver(
            FakeLookup(message("", "Alice", payloads = listOf(photo), messageContent = MessageContent.ViewOnce(null))),
            loadImage = { error("view-once media must not reach a notification") },
        ).resolvePreview(Uuid.random(), Uuid.random())
        assertNull(preview?.imageBytes)
        assertTrue(preview?.preview.orEmpty().isNotBlank())
    }

    @Test
    fun textMessage_loadsNoImage() = runTest {
        val preview = ChatNotificationMessageResolver(
            FakeLookup(message("Hi", "Alice")),
            loadImage = { error("no media to load") },
        ).resolvePreview(Uuid.random(), Uuid.random())
        assertNull(preview?.imageBytes)
    }

    @Test
    fun groupConversation_yieldsItsName() = runTest {
        val preview = ChatNotificationMessageResolver(
            FakeLookup(message("Hi", "Alice")),
            conversation = { conversation(isGroup = true, name = "Fellowship") },
        ).resolvePreview(Uuid.random(), Uuid.random())
        assertEquals(true, preview?.isGroup)
        assertEquals("Fellowship", preview?.groupTitle)
    }

    @Test
    fun oneToOneConversation_isNotAGroup() = runTest {
        val preview = ChatNotificationMessageResolver(
            FakeLookup(message("Hi", "Alice")),
            conversation = { conversation(isGroup = false, name = "Alice") },
        ).resolvePreview(Uuid.random(), Uuid.random())
        assertEquals(false, preview?.isGroup)
        assertNull(preview?.groupTitle)
    }
}
