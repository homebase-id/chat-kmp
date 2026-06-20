package id.homebase.core.ui.screens.location.livelocation

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.liverelay.LIVE_LOCATION_CHANNEL_KEY
import id.homebase.api.client.liverelay.LiveLocationCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Debug-flow receiver for Live Relay live-location blobs. Subscribes to [EventBus], decodes inbound
 * [BackendEvent.LiveRelayReceived] on the well-known [LIVE_LOCATION_CHANNEL_KEY], and logs the
 * decoded position + freshness. This is the receive-side instrumentation for the "confirm the data
 * flows" build — no UI yet; a follow-up plan turns these events into a map.
 */
class LiveLocationDebugLogger(
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val logger = Logger.withTag(TAG)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.LiveRelayReceived) return@collect
                if (event.channelKey != LIVE_LOCATION_CHANNEL_KEY) return@collect
                val pt = LiveLocationCodec.decode(event.blob)
                if (pt == null) {
                    logger.w {
                        "RECV-DECODE-FAIL from=${event.senderOdinId.domainName} bytes=${event.blob.length}"
                    }
                    return@collect
                }
                logger.i {
                    "RECV-DECODED from=${event.senderOdinId.domainName} lat=${pt.lat} lon=${pt.lon} " +
                        "ageMs=${nowMs() - event.receivedAt}"
                }
            }
        }
    }

    fun reset() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "LiveRelay"
    }
}
