package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.test.runTest
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

    private fun message(content: String, displayName: String): MessageUiModel = MessageUiModel(
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
        payloads = null,
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
}
