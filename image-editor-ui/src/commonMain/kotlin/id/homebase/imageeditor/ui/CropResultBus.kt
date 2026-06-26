package id.homebase.imageeditor.ui

import id.homebase.api.image.ImageResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.uuid.Uuid

/**
 * Bridges the cropper screen back to its caller. Compose Navigation
 * `SavedStateHandle` is awkward for `ByteArray` results; a small in-memory
 * bus is simpler and matches the "ephemeral, single-consumer" semantics.
 *
 * Inputs come in via [postSource]: the caller publishes the input [ByteArray]
 * keyed by a request id, navigates to the cropper screen, and the cropper
 * looks it up via [takeSource]. Results go back via [postResult] / a
 * [resultsFor] flow scoped to the request.
 */
class CropResultBus {
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
        // Channel per crop, for the lifetime of the long-lived ViewModel. The editor
        // VM's onCleared() also cancel()s for the abort (no-result) path.
        channel.close()
    }

    fun cancel(requestId: Uuid) {
        sources.remove(requestId)
        results.remove(requestId)?.close()
    }
}
