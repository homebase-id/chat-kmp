package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MessageListKeyTest {

    private val me = OdinId("frodo.test")

    private fun message(id: Uuid, versionTag: Uuid, isPendingSend: Boolean = false, content: String = "hi") =
        MessageListContentModel.Message(
            MessageUiModel(
                id = id,
                globalTransitId = null,
                fileId = Uuid.random(),
                conversationId = Uuid.NIL,
                content = content,
                userDate = Instant.fromEpochMilliseconds(0),
                modified = null,
                created = Instant.fromEpochMilliseconds(0),
                originalAuthor = me,
                sender = me,
                displayName = "",
                messageAppData = MessageAppData(),
                reactionPreview = null,
                previewThumbnail = null,
                payloads = null,
                keyHeader = KeyHeader.empty(),
                isDeleted = false,
                versionTag = versionTag,
                isPendingSend = isPendingSend,
                hasMore = false,
            )
        )

    @Test
    fun placeholderOutboxRowAndServerVersionsShareOneKey() {
        val id = Uuid.random()
        val placeholder = PendingOutgoingMessage(
            id = id,
            conversationId = Uuid.NIL,
            text = "hi",
            attachmentCount = 1,
            sentAt = Instant.fromEpochMilliseconds(0),
        )
        val keys = listOf(
            messageListKey(placeholder),
            messageListKey(message(id, Uuid.NIL, isPendingSend = true)),
            messageListKey(message(id, Uuid.random())),
            messageListKey(message(id, Uuid.random(), content = "edited")),
        )
        assertEquals(1, keys.toSet().size, keys.toString())
    }

    @Test
    fun distinctMessagesKeepDistinctKeys() {
        assertNotEquals(
            messageListKey(message(Uuid.random(), Uuid.NIL)),
            messageListKey(message(Uuid.random(), Uuid.NIL)),
        )
    }
}
