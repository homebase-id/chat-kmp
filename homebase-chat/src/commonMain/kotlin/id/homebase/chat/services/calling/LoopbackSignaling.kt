package id.homebase.chat.services.calling

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class LoopbackSignaling(
    private val latencyMs: Long = 0,
    private val now: () -> Long = { 0L },
) {
    data class Sent(val from: String, val to: String, val signal: CallSignal)

    private val inboxes = mutableMapOf<String, MutableSharedFlow<InboundSignal>>()
    private val _sent = mutableListOf<Sent>()
    val sent: List<Sent> get() = _sent

    fun endpoint(selfOdinId: String): CallSignaling = object : CallSignaling {
        override val incoming: SharedFlow<InboundSignal> = inbox(selfOdinId).asSharedFlow()

        override suspend fun send(peer: String, signal: CallSignal) {
            _sent += Sent(selfOdinId, peer, signal)
            if (latencyMs > 0) delay(latencyMs)
            deliver(peer, selfOdinId, signal)
        }
    }

    fun deliver(to: String, from: String, signal: CallSignal, receivedAtMs: Long = now()) {
        inbox(to).tryEmit(InboundSignal(from, signal, receivedAtMs))
    }

    private fun inbox(odinId: String) =
        inboxes.getOrPut(odinId) { MutableSharedFlow(extraBufferCapacity = 256) }
}
