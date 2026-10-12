package id.homebase.chat.services.calling

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.liverelay.LiveRelayPush
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull

typealias LiveRelaySend = suspend (
    channelKey: String,
    recipients: List<String>,
    blob: String,
    push: LiveRelayPush?,
) -> Boolean

class LiveRelayCallSignaling(
    selfOdinId: String,
    private val relay: LiveRelaySend,
    eventBus: EventBus,
) : CallSignaling {
    private val ownChannel = CallChannel.keyFor(selfOdinId)

    override val incoming: Flow<InboundSignal> = eventBus.events
        .filterIsInstance<BackendEvent.LiveRelayReceived>()
        .filter { it.channelKey.equals(ownChannel, ignoreCase = true) }
        .mapNotNull { event ->
            CallSignalCodec.decode(event.blob)?.let { InboundSignal(event.senderOdinId.domainName, it, event.receivedAt) }
        }

    override suspend fun send(outbound: OutboundSignal) {
        val push = outbound.push?.let {
            LiveRelayPush(kind = it.kind.wire, typeId = it.typeId, tagId = it.tagId, unEncryptedMessage = it.message)
        }
        val accepted = relay(outbound.channelKey, listOf(outbound.peer), CallSignalCodec.encode(outbound.signal), push)
        // A lost ICE candidate or hangup is recoverable; an offer the relay refused means nobody can ring.
        if (!accepted && outbound.signal is CallSignal.Offer) {
            throw CallEngineException(FailReason.NoRoute, "relay refused the offer")
        }
    }
}
