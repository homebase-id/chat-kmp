package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.CancellationException

fun interface SurfacedListener {
    suspend fun onSurfaced(messages: List<MessageUiModel>)
}

internal suspend fun List<SurfacedListener>.runAll(messages: List<MessageUiModel>) {
    if (messages.isEmpty()) return
    for (listener in this) {
        try {
            listener.onSurfaced(messages)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.e(t) { "ChatMessageStream: surfaced listener failed: ${t.message}" }
        }
    }
}
