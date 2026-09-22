package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.content.MessageContent
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class LastEditableMessageTest {

    private val me = OdinId("frodo.test")
    private val alice = OdinId("alice.test")

    private fun message(
        author: OdinId = me,
        isDeleted: Boolean = false,
        isPendingSend: Boolean = false,
        isFailedSend: Boolean = false,
        versionTag: Uuid = Uuid.random(),
        messageContent: MessageContent? = null,
    ) = MessageListContentModel.Message(
        MessageUiModel(
            id = Uuid.random(),
            globalTransitId = null,
            fileId = Uuid.random(),
            conversationId = Uuid.NIL,
            content = "x",
            userDate = Instant.fromEpochMilliseconds(0),
            modified = null,
            created = Instant.fromEpochMilliseconds(0),
            originalAuthor = author,
            sender = author,
            displayName = "",
            messageAppData = MessageAppData(),
            reactionPreview = null,
            previewThumbnail = null,
            payloads = null,
            keyHeader = KeyHeader.empty(),
            isDeleted = isDeleted,
            versionTag = versionTag,
            isPendingSend = isPendingSend,
            isFailedSend = isFailedSend,
            messageContent = messageContent,
            hasMore = false,
        )
    )

    private fun state(
        vararg items: MessageListContentModel,
        isEditingMessageId: Uuid? = null,
        hasNewerMessages: Boolean = false,
    ) = MessageListUiState(
        messages = items.toList().toImmutableList(),
        ownerSession = OwnerSession(me, null, null, null, null, null, null, null),
        isEditingMessageId = isEditingMessageId,
        hasNewerMessages = hasNewerMessages,
    )

    @Test
    fun `walks back past everything I cannot edit`() {
        val target = message()
        val result = state(
            MessageListContentModel.Header,
            message(),
            target,
            message(author = alice),
            message(messageContent = MessageContent.Poll(null)),
            message(messageContent = MessageContent.Event(null)),
            message(isDeleted = true),
            // No server version tag and no queued create to amend: a failed create, and a
            // landed create whose version tag hasn't synced back yet.
            message(isFailedSend = true, versionTag = Uuid.NIL),
            message(versionTag = Uuid.NIL),
            MessageListContentModel.System("joined", Instant.fromEpochMilliseconds(0), 0),
        ).lastEditableMessage()

        assertEquals(target.message, result)
    }

    @Test
    fun `a queued create and a just saved edit are both editable`() {
        val queuedCreate = message(isPendingSend = true, versionTag = Uuid.NIL)
        assertEquals(queuedCreate.message, state(message(), queuedCreate).lastEditableMessage())

        val savedEdit = message(isPendingSend = true)
        assertEquals(savedEdit.message, state(message(), savedEdit).lastEditableMessage())
    }

    @Test
    fun `a failed edit of a delivered message is still editable`() {
        val failedEdit = message(isFailedSend = true)
        assertEquals(failedEdit.message, state(message(), failedEdit).lastEditableMessage())
    }

    @Test
    fun `nothing of mine to edit does nothing`() {
        assertNull(state(message(author = alice), message(isDeleted = true)).lastEditableMessage())
        assertNull(state().lastEditableMessage())
    }

    @Test
    fun `an edit already in progress is left alone`() {
        val editing = message()
        assertNull(state(editing, isEditingMessageId = editing.message.id).lastEditableMessage())
    }

    @Test
    fun `a window paged into history does not hold the last sent message`() {
        assertNull(state(message(), hasNewerMessages = true).lastEditableMessage())
    }
}
