package id.homebase.chat.services.calling

import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

class LoopbackSignaling(
    private val latencyMs: Long = 0,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    data class Sent(val from: String, val to: String, val signal: CallSignal, val channelKey: String, val push: CallPush?)

    private val inboxes = MutableStateFlow<Map<String, MutableSharedFlow<InboundSignal>>>(emptyMap())
    private val _sent = MutableStateFlow<List<Sent>>(emptyList())
    val sent: List<Sent> get() = _sent.value

    fun endpoint(selfOdinId: String): CallSignaling = object : CallSignaling {
        override val incoming: SharedFlow<InboundSignal> = inbox(selfOdinId).asSharedFlow()

        override suspend fun send(outbound: OutboundSignal) {
            _sent.update { it + Sent(selfOdinId, outbound.peer, outbound.signal, outbound.channelKey, outbound.push) }
            if (latencyMs > 0) delay(latencyMs)
            // Like the relay: the receiver only sees channels it has open (its own key).
            if (outbound.channelKey == CallChannel.keyFor(outbound.peer)) deliver(outbound.peer, selfOdinId, outbound.signal)
        }
    }

    fun deliver(to: String, from: String, signal: CallSignal, receivedAtMs: Long = now()) {
        inbox(to).tryEmit(InboundSignal(from, signal, receivedAtMs))
    }

    // Copy-on-write CAS: concurrent first lookups converge on one inbox, so no signal lands in a discarded flow.
    private fun inbox(odinId: String): MutableSharedFlow<InboundSignal> {
        inboxes.value[odinId]?.let { return it }
        val created = MutableSharedFlow<InboundSignal>(extraBufferCapacity = 256)
        return inboxes.updateAndGet { if (odinId in it) it else it + (odinId to created) }.getValue(odinId)
    }
}
