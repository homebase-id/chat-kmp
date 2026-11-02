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
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SharedFilesToTargetsTest {

    private val first = Uuid.random()
    private val second = Uuid.random()
    private val state = MutableStateFlow(MessageListUiState())

    private fun pendingFor(conversationId: Uuid) =
        state.value.pendingOutgoing.filter { it.conversationId == conversationId }

    @Test
    fun everyTargetShowsAPlaceholderBeforeTheSuspendedSendReturns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Uuid>()

        val job = async {
            state.sendSharedFilesToTargets(
                targets = listOf(first, second),
                visibleConversationId = first,
                text = "look",
                attachmentCount = 1,
                sentAt = Instant.fromEpochMilliseconds(0),
            ) { _, conversationId ->
                gate.await()
                sent += conversationId
            }
        }
        runCurrent()

        assertEquals(1, pendingFor(first).size)
        assertEquals(1, pendingFor(second).size)
        assertTrue(sent.isEmpty())

        gate.complete(Unit)
        job.await()

        assertEquals(listOf(first, second), sent)
        assertTrue(state.value.pendingOutgoing.isEmpty())
    }

    @Test
    fun aFailedSendDropsItsPlaceholderAndStillSendsTheRest() = runTest {
        val sent = mutableListOf<Uuid>()

        assertFailsWith<IllegalStateException> {
            state.sendSharedFilesToTargets(
                targets = listOf(first, second),
                visibleConversationId = first,
                text = "",
                attachmentCount = 2,
                sentAt = Instant.fromEpochMilliseconds(0),
            ) { _, conversationId ->
                if (conversationId == first) error("source gone")
                sent += conversationId
            }
        }

        assertEquals(listOf(second), sent)
        assertTrue(state.value.pendingOutgoing.isEmpty())
    }

    @Test
    fun filesAreDeletedOnlyAfterBothSendsFinish() = runTest {
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()

        val job = async {
            try {
                state.sendSharedFilesToTargets(
                    targets = listOf(first, second),
                    visibleConversationId = second,
                    text = "",
                    attachmentCount = 1,
                    sentAt = Instant.fromEpochMilliseconds(0),
                ) { _, conversationId ->
                    if (conversationId == second) gate.await()
                    events += "sent"
                }
            } finally {
                events += "cleanup"
            }
        }
        runCurrent()
        assertEquals(listOf("sent"), events)

        gate.complete(Unit)
        state.update { it.copy(hasNewerMessages = true) }
        job.await()

        assertEquals(listOf("sent", "sent", "cleanup"), events)
    }

    @Test
    fun theVisiblePlaceholderStaysUntilTheRealMessageLands() = runTest {
        val job = async {
            state.sendSharedFilesToTargets(
                targets = listOf(first),
                visibleConversationId = first,
                text = "",
                attachmentCount = 1,
                sentAt = Instant.fromEpochMilliseconds(0),
            ) { _, _ -> }
        }
        runCurrent()
        assertEquals(1, pendingFor(first).size)

        state.update { it.copy(hasNewerMessages = true) }
        job.await()

        assertTrue(pendingFor(first).isEmpty())
    }
}
