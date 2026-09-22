package id.homebase.chat.conversationlist

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the single-slot `uiState.uiEvent` this replaced.
 *
 * The old mechanism was one nullable field written by `sendEvent` and cleared by
 * `eventConsumed`, so a second send before the screen consumed the first simply
 * overwrote it. Swiping several rows to archive in quick succession therefore showed
 * only the last undo snackbar, and the earlier archives became unrecoverable from the UI.
 */
class ConversationListEventsTest {

    @Test
    fun rapidSuccessiveEventsAreAllDelivered() = runTest {
        val events = ConversationListEvents()
        val sent = (1..5).map { ConversationListUiEvent.NavigateToContactInfo("peer$it") }

        sent.forEach(events::send)

        assertEquals(sent, events.events.take(sent.size).toList())
    }

    @Test
    fun eventsSentBeforeACollectorAttachesSurvive() = runTest {
        val events = ConversationListEvents()
        events.send(ConversationListUiEvent.NavigateBack)

        assertEquals(
            listOf(ConversationListUiEvent.NavigateBack),
            events.events.take(1).toList(),
        )
    }

    @Test
    fun collectingTwiceDoesNotReplayAlreadyHandledEvents() = runTest {
        val events = ConversationListEvents()
        events.send(ConversationListUiEvent.NavigateToGroupSettings("first"))
        assertEquals(1, events.events.take(1).toList().size)

        events.send(ConversationListUiEvent.NavigateToGroupSettings("second"))

        assertEquals(
            listOf(ConversationListUiEvent.NavigateToGroupSettings("second")),
            events.events.take(1).toList(),
        )
    }
}
