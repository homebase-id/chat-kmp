package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One-time UI events for the conversation list. Buffered because the single
 * `uiState.uiEvent` slot this replaced overwrote an unconsumed event.
 */
internal class ConversationListEvents {
    private val channel = Channel<ConversationListUiEvent>(capacity = Channel.BUFFERED)

    val events: Flow<ConversationListUiEvent> = channel.receiveAsFlow()

    fun send(event: ConversationListUiEvent) {
        channel.trySend(event).onFailure {
            Logger.w(throwable = it, tag = "ConversationListEvents") {
                "dropped ${event::class.simpleName}"
            }
        }
    }
}
