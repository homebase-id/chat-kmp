package id.homebase.core.ui.screens.location.livelocation

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.liverelay.LIVE_LOCATION_CHANNEL_KEY
import id.homebase.api.client.liverelay.LiveLocationCodec
import id.homebase.api.client.liverelay.LiveLocationPoint
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** A friend's last known live position. [receivedAtMs] is the server-stamped receipt time. */
data class LivePosition(
    val senderOdinId: OdinId,
    val point: LiveLocationPoint,
    val receivedAtMs: Long,
)

/**
 * In-memory store of the last known live position per sender, fed from the notification websocket
 * (`BackendEvent.LiveRelayReceived`). This is what a future map/dashboard binds to.
 *
 * **Hydration — why this is in-memory only (never persisted):** Live Relay is ephemeral and the
 * SERVER is the source of truth. It retains each sender's last point (TTL ~5 min) and **auto-flushes
 * every sender's last point on (re)connect/foreground**, so this map starts empty on a cold start
 * and refills from the server within a second of the socket connecting. Persisting received
 * positions would (a) duplicate the server's retained store and (b) risk rendering positions
 * *staler than the server will ever serve* (e.g. an hour-old "ghost" for a feature called *live*).
 * Contrast the **send** side, whose roster IS persisted — that's the user's own intent and must
 * survive a cold background wake; received positions are someone else's ephemeral data.
 *
 * Last-value-wins per [OdinId]. Cleared on logout. Staleness is derived in the UI from
 * [LivePosition.receivedAtMs] (age = now − receivedAt) to fade and eventually drop a marker.
 */
class LiveLocationReceiveStore(
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val logger = Logger.withTag(TAG)
    private var job: Job? = null

    private val _positions = MutableStateFlow<Map<OdinId, LivePosition>>(emptyMap())
    val positions: StateFlow<Map<OdinId, LivePosition>> = _positions.asStateFlow()

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
                _positions.update { current ->
                    current + (event.senderOdinId to LivePosition(event.senderOdinId, pt, event.receivedAt))
                }
                logger.i {
                    "RECV-DECODED from=${event.senderOdinId.domainName} lat=${pt.lat} lon=${pt.lon} " +
                        "ageMs=${nowMs() - event.receivedAt} tracked=${_positions.value.size}"
                }
            }
        }
    }

    /** Stop collecting and drop all positions (logout). They rehydrate from the server on next login. */
    fun reset() {
        job?.cancel()
        job = null
        _positions.value = emptyMap()
    }

    companion object {
        private const val TAG = "LiveRelay"
    }
}
