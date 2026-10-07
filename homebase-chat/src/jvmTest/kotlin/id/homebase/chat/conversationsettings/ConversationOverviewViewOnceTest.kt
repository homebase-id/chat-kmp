package id.homebase.chat.conversationsettings

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.viewonce.ViewOnceDescriptor
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationOverviewViewOnceTest {

    private fun imageMessage(content: MessageContent?): MessageUiModel = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = OdinId("alice.test"),
        sender = OdinId("alice.test"),
        displayName = "",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = persistentListOf(PayloadDescriptor(key = "chat_web0", contentType = "image/jpeg")),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        versionTag = Uuid.random(),
        isPendingSend = false,
        messageContent = content,
        hasMore = false,
    )

    @Test
    fun `view-once and unknown-kind payloads never reach shared media`() {
        val plain = imageMessage(null)
        val viewOnce = imageMessage(MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE)))
        val unparseable = imageMessage(MessageContent.ViewOnce(null))
        val unknown = imageMessage(MessageContent.Unknown(217))

        val overview = collectConversationOverview(
            BatchResult(
                records = listOf(plain, viewOnce, unparseable, unknown),
                hasMoreRows = false,
                cursor = QueryBatchCursor(),
            )
        )

        assertEquals(listOf(plain.id), overview.media.map { it.messageId })
    }
}
