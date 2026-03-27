package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ActiveConversationStateTest {

    private fun message(id: Uuid = Uuid.random(), conversationId: Uuid = Uuid.random()) =
        MessageUiModel(
            id = id,
            globalTransitId = null,
            fileId = Uuid.random(),
            conversationId = conversationId,
            content = "test",
            created = Instant.fromEpochMilliseconds(0),
            modified = null,
            originalAuthor = null,
            displayName = "Tester",
            messageAppData = MessageAppData(),
            reactionPreview = null,
            previewThumbnail = null,
            payloads = null,
            keyHeader = KeyHeader(
                iv = ByteArray(16),
                aesKey = SecureByteArray(ByteArray(16))
            ),
            versionTag = Uuid.random(),
            isPendingSend = false,
            hasMore = false,
        )

    @Test
    fun removeMessage_removesTargetAndLeavesOthers() {
        val state = ActiveConversationState()
        val convoId = Uuid.random()
        val target = message(conversationId = convoId)
        val other = message(conversationId = convoId)
        state.set(convoId, listOf(target, other))

        state.removeMessage(target.id)

        val remaining = state.messages.value[convoId]!!
        assertEquals(1, remaining.size)
        assertEquals(other.id, remaining[0].id)
    }

    @Test
    fun removeMessage_unknownId_isNoOp() {
        val state = ActiveConversationState()
        val convoId = Uuid.random()
        val msg = message(conversationId = convoId)
        state.set(convoId, listOf(msg))

        state.removeMessage(Uuid.random())

        assertEquals(1, state.messages.value[convoId]!!.size)
    }

    @Test
    fun removeMessage_acrossConversations_onlyRemovesCorrectOne() {
        val state = ActiveConversationState()
        val convo1 = Uuid.random()
        val convo2 = Uuid.random()
        val target = message(conversationId = convo1)
        val unrelated = message(conversationId = convo2)
        state.set(convo1, listOf(target))
        state.set(convo2, listOf(unrelated))

        state.removeMessage(target.id)

        assertNull(state.messages.value[convo1]!!.firstOrNull { it.id == target.id })
        assertEquals(1, state.messages.value[convo2]!!.size)
    }
}
