package id.homebase.chat.services.livelocation

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.liverelay.LIVE_LOCATION_CHANNEL_KEY
import id.homebase.api.client.liverelay.LiveLocationCodec
import id.homebase.api.client.liverelay.LiveLocationPoint
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
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

    private val _positions = MutableStateFlow<Map<OdinId, LivePosition>>(emptyMap())
    val positions: StateFlow<Map<OdinId, LivePosition>> = _positions.asStateFlow()

    // The collector runs for the whole app lifetime on the app-scope (SupervisorJob, never
    // cancelled on logout), so a received packet always has a live consumer — independent of
    // whether the fragile onPostAuthenticated bootstrap ran, was deferred (headless), or a
    // reconnect happened. Logout is handled in-stream via SessionEnded; see [reset]. (Bug #824:
    // the old start()/reset()-job model left the collector dead whenever start() wasn't reached.)
    init {
        scope.launch { observeEvents() }
    }

    private suspend fun observeEvents() {
        logger.i { "receive collector started" }
        // Unlike ContactBookStream, we deliberately do NOT drop the replay buffer: EventBus
        // replay=1 lets a slightly-late collector still catch the server's last flush-on-connect
        // point, and a stale replayed position is harmless (overwritten; aged out by receivedAt).
        eventBus.events.collect { event ->
            when (event) {
                is BackendEvent.SessionEnded -> reset()

                is BackendEvent.LiveRelayReceived -> {
                    if (event.channelKey != LIVE_LOCATION_CHANNEL_KEY) {
                        logger.d {
                            "RECV-DROP wrong-channel from=${event.senderOdinId.domainName} ch=${event.channelKey}"
                        }
                        return@collect
                    }
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

                else -> {}
            }
        }
    }

    /**
     * Drop all positions (logout / new identity). The collector is NOT cancelled — it stays
     * subscribed for the app's lifetime; positions rehydrate from the server's flush-on-connect
     * after the next login. Called in-stream on [BackendEvent.SessionEnded] (same coroutine as the
     * flush, so it can't race it). [reason] is logged so a clear landing right after a
     * `RECV-DECODED` — the fingerprint of a flush being clobbered (#1072) — is diagnosable.
     */
    fun reset(reason: String = "sessionEnded") {
        logger.i { "reset(reason=$reason) clearing ${_positions.value.size} sender(s)" }
        _positions.value = emptyMap()
    }

    companion object {
        private const val TAG = "LiveRelay"
    }
}
