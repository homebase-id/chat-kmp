package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PendingSendPlaceholderTest {

    private val id = Uuid.random()
    private val placeholder = PendingOutgoingMessage(
        id = id,
        conversationId = Uuid.random(),
        text = "",
        attachmentCount = 1,
        sentAt = Instant.fromEpochMilliseconds(0),
    )

    private fun registered() = MutableStateFlow(MessageListUiState()).apply {
        update { it.withPendingSend(placeholder) }
    }

    private fun realMessage(messageId: Uuid): MessageListContentModel.Message {
        val author = OdinId("alice.test")
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        return MessageListContentModel.Message(
            MessageUiModel(
                id = messageId, globalTransitId = null, fileId = Uuid.random(),
                conversationId = Uuid.random(), content = "x", userDate = now, modified = null,
                created = now, originalAuthor = author, sender = author, displayName = author.domainName,
                localReadTimestamp = null, isDeleted = false, isPendingSend = false,
                versionTag = Uuid.NIL, messageAppData = MessageAppData(), reactionPreview = null,
                previewThumbnail = null, payloads = null, keyHeader = KeyHeader.empty(), hasMore = false,
            ),
        )
    }

    @Test
    fun placeholderStaysUntilRealMessageIsInTheList() = runTest {
        val state = registered()

        val send = async { state.sendUnderPlaceholder(id) { } }
        runCurrent()

        assertEquals(listOf(id), state.value.pendingOutgoing.map { it.id })

        state.update { it.copy(messages = persistentListOf(realMessage(id))) }
        send.await()

        assertTrue(state.value.pendingOutgoing.isEmpty())
        assertEquals(UploadStatus.Preparing, state.value.uploadProgress[id])
    }

    @Test
    fun placeholderIsDroppedAfterTimeoutWhenRealMessageNeverArrives() = runTest {
        val state = registered()

        val send = async { state.sendUnderPlaceholder(id) { } }
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(1, state.value.pendingOutgoing.size)

        advanceUntilIdle()
        send.await()
        assertTrue(state.value.pendingOutgoing.isEmpty())
    }

    @Test
    fun pagedBackInHistoryDoesNotWaitForRealMessage() = runTest {
        val state = registered()
        state.update { it.copy(hasNewerMessages = true) }

        state.sendUnderPlaceholder(id) { }

        assertEquals(0L, testScheduler.currentTime)
        assertTrue(state.value.pendingOutgoing.isEmpty())
    }

    @Test
    fun placeholderShowsPreparingWhileSendIsSuspended_thenDropsKeepingProgress() = runTest {
        val state = registered()
        val gate = CompletableDeferred<Unit>()

        val send = async { state.sendUnderPlaceholder(id) { gate.await() } }
        runCurrent()

        assertEquals(listOf(id), state.value.pendingOutgoing.map { it.id })
        assertEquals(UploadStatus.Preparing, state.value.uploadProgress[id])

        gate.complete(Unit)
        state.update { it.copy(messages = persistentListOf(realMessage(id))) }
        send.await()

        assertTrue(state.value.pendingOutgoing.isEmpty())
        assertEquals(UploadStatus.Preparing, state.value.uploadProgress[id])
    }

    @Test
    fun failedSendDropsPlaceholderAndProgressAndRethrows() = runTest {
        val state = registered()
        val boom = IllegalStateException("bundle failed")

        val thrown = assertFailsWith<IllegalStateException> {
            state.sendUnderPlaceholder(id) { throw boom }
        }

        assertSame(boom, thrown)
        assertTrue(state.value.pendingOutgoing.isEmpty())
        assertTrue(id !in state.value.uploadProgress)
    }
}
