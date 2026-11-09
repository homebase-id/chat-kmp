package id.homebase.chat.conversationlist

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class SendToEveryTargetTest {

    private val a = Uuid.random()
    private val b = Uuid.random()
    private val c = Uuid.random()

    @Test
    fun aShareClaimedOnOpeningASendsToAAndB() = runTest {
        val sent = mutableListOf<Uuid>()

        sendToEveryTarget(listOf(a, b), visibleConversationId = a) { sent += it }

        assertEquals(listOf(a, b), sent)
    }

    @Test
    fun theVisibleChatGoesFirst() = runTest {
        val sent = mutableListOf<Uuid>()

        sendToEveryTarget(listOf(a, b, c), visibleConversationId = b) { sent += it }

        assertEquals(b, sent.first())
        assertEquals(setOf(a, b, c), sent.toSet())
    }

    @Test
    fun aFailedTargetDoesNotStopTheOthersAndTheFailureSurfaces() = runTest {
        val sent = mutableListOf<Uuid>()

        val failure = assertFailsWith<IllegalStateException> {
            sendToEveryTarget(listOf(a, b, c), visibleConversationId = a) {
                if (it == b) error("boom")
                sent += it
            }
        }

        assertEquals("boom", failure.message)
        assertEquals(listOf(a, c), sent)
    }
}
