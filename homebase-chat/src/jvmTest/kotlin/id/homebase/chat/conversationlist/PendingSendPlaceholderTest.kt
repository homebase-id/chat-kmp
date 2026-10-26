package id.homebase.chat.conversationlist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
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

    @Test
    fun placeholderShowsPreparingWhileSendIsSuspended_thenDropsKeepingProgress() = runTest {
        val state = registered()
        val gate = CompletableDeferred<Unit>()

        val send = async { state.sendUnderPlaceholder(id) { gate.await() } }
        runCurrent()

        assertEquals(listOf(id), state.value.pendingOutgoing.map { it.id })
        assertEquals(UploadStatus.Preparing, state.value.uploadProgress[id])

        gate.complete(Unit)
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
