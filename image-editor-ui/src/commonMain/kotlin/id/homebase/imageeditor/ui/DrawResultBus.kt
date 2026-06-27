package id.homebase.imageeditor.ui

import id.homebase.api.image.ImageResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.uuid.Uuid

/**
 * Bridges the draw screen back to its caller. Same shape as [CropResultBus]
 * — kept separate for clarity; if a third editor mode lands they can be
 * folded together.
 */
class DrawResultBus {
    private val sources: MutableMap<Uuid, ByteArray> = mutableMapOf()
    private val results: MutableMap<Uuid, Channel<ImageResult>> = mutableMapOf()

    fun postSource(requestId: Uuid, bytes: ByteArray) {
        sources[requestId] = bytes
    }

    fun takeSource(requestId: Uuid): ByteArray? = sources.remove(requestId)

    fun resultsFor(requestId: Uuid): Flow<ImageResult> {
        val channel = results.getOrPut(requestId) { Channel(capacity = Channel.BUFFERED) }
        return channel.consumeAsFlow()
    }

    suspend fun postResult(requestId: Uuid, result: ImageResult) {
        val channel = results.getOrPut(requestId) { Channel(capacity = Channel.BUFFERED) }
        channel.send(result)
        // Single-shot: close so resultsFor(requestId) completes after this one result.
        // Without it the caller's collect{} suspends forever — a leaked coroutine +
        // Channel per draw, for the lifetime of the long-lived ViewModel. The editor
        // VM's onCleared() also cancel()s for the abort (no-result) path.
        channel.close()
    }

    fun cancel(requestId: Uuid) {
        sources.remove(requestId)
        results.remove(requestId)?.close()
    }
}
