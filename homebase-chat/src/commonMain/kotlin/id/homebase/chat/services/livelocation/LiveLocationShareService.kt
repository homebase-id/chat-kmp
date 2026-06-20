package id.homebase.chat.services.livelocation

import co.touchlab.kermit.Logger
import id.homebase.api.client.liverelay.LIVE_LOCATION_CHANNEL_KEY
import id.homebase.api.client.liverelay.LiveLocationCodec
import id.homebase.api.client.liverelay.LiveLocationPoint
import id.homebase.api.client.liverelay.LiveRelayProvider
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.location.tracking.LocationTracker
import id.homebase.core.location.tracking.TrackingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Live-location sender for the Live Relay debug-flow build. While a share is active it relays the
 * device's latest GPS fix to a recipient set over [LiveRelayProvider], throttled to [MIN_INTERVAL_MS]
 * (last-value-wins).
 *
 * **Where the send fires:** [onGpsBuffered] is invoked from the `onPointsBuffered` seam in
 * `LocationPointStore.submit()` (wired in `AppModule`) — the only hook that runs on OS-delivered
 * points while the app is **backgrounded or cold-woken** (Android `LocationUpdatesReceiver`, iOS
 * `CLLocationManager` delegate). It deliberately does NOT collect `lastPoint` as a Flow: that
 * StateFlow is only observed by UI, so a collector would silently stop sending once backgrounded.
 *
 * **Persisted state:** `active`/`recipients` are stored in keyValue so a `BroadcastReceiver`-woken
 * cold process (which rebuilds this singleton from scratch) still knows to relay. The fixed
 * [LIVE_LOCATION_CHANNEL_KEY] needs no persistence.
 *
 * Ephemeral by design — no drive writes, no outbox; the relay is a direct fire-and-forget POST. The
 * durable hourly track files (`LocationTrackUploaderService`) are unaffected and run alongside this.
 */
class LiveLocationShareService(
    private val liveRelayProvider: LiveRelayProvider,
    private val locationPointStore: LocationPointStore,
    private val locationTracker: LocationTracker,
    private val locationPreferences: LocationPreferences,
    private val databaseManager: DatabaseManager,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val logger = Logger.withTag(TAG)

    private val state = MutableStateFlow(readPersisted())

    // Authoritative throttle bookkeeping — only touched under [sendLock].
    private val sendLock = Mutex()
    private var lastSentMs = 0L

    fun isActive(): Boolean = state.value.active

    /** Begin sharing live location with [recipients] (connected identities). */
    suspend fun start(recipients: List<OdinId>) {
        val domains = recipients.map { it.domainName }
        // Ensure GPS is actually flowing. If the user's tracking master switch is off, the coordinator
        // never started the tracker — start it ourselves and remember to stop it on [stop].
        val startedByUs =
            if (!locationPreferences.trackingEnabled.value && locationTracker.isAvailable) {
                locationTracker.start(TrackingMode.Foreground)
                true
            } else {
                false
            }
        val next = LiveShareState(active = true, recipients = domains, startedTrackerByUs = startedByUs)
        state.value = next
        persist(next)
        logger.i { "START recipients=${domains.size} startedTracker=$startedByUs" }
    }

    /** Stop sharing. If we started the tracker (tracking switch was off), stop it again. */
    suspend fun stop() {
        val cur = state.value
        if (cur.startedTrackerByUs) locationTracker.stop()
        val next = LiveShareState()
        state.value = next
        persist(next)
        logger.i { "STOP" }
    }

    /**
     * Called from the GPS sink (`onPointsBuffered`) after each accepted batch — background-capable.
     * Relays the just-accepted latest point if a share is active and the throttle window has elapsed.
     * Never throws into the sink chain.
     */
    suspend fun onGpsBuffered() {
        val cur = state.value
        if (!cur.active) return
        if (nowMs() - lastSentMs < MIN_INTERVAL_MS) return
        // Skip (don't queue) if a send is already in flight — last-value-wins, the next batch supersedes.
        if (!sendLock.tryLock()) return
        try {
            val now = nowMs()
            if (now - lastSentMs < MIN_INTERVAL_MS) return
            // Read the just-set latest point synchronously (valid in background — submit() sets
            // _lastPoint.value on the line before it calls onPointsBuffered; we read .value, we do
            // NOT collect the Flow).
            val p = locationPointStore.lastPoint.value ?: return
            val blob = LiveLocationCodec.encode(
                LiveLocationPoint(lat = p.lat, lon = p.lon, acc = p.acc, spd = p.spd, hdg = p.hdg, ts = p.t)
            )
            runCatching { liveRelayProvider.relay(LIVE_LOCATION_CHANNEL_KEY, cur.recipients, blob) }
                .onFailure { logger.w(it) { "relay failed" } }
            lastSentMs = now
        } finally {
            sendLock.unlock()
        }
    }

    /** Clear in-memory + persisted state on logout. */
    fun reset() {
        val cur = state.value
        if (cur.startedTrackerByUs) locationTracker.stop()
        state.value = LiveShareState()
        lastSentMs = 0L
        scope.launch { persist(LiveShareState()) }
    }

    private fun readPersisted(): LiveShareState =
        runCatching {
            val bytes = databaseManager.keyValue.selectByKeyBootstrapSync(STATE_KEY) { _, data -> data }
                ?: return LiveShareState()
            if (bytes.isEmpty()) LiveShareState()
            else OdinSystemSerializer.deserialize<LiveShareState>(bytes.decodeToString())
        }.getOrDefault(LiveShareState())

    private suspend fun persist(s: LiveShareState) {
        runCatching {
            databaseManager.keyValue.upsertValue(STATE_KEY, OdinSystemSerializer.serialize(s).encodeToByteArray())
        }.onFailure { logger.w(it) { "persist failed" } }
    }

    @Serializable
    private data class LiveShareState(
        val active: Boolean = false,
        val recipients: List<String> = emptyList(),
        val startedTrackerByUs: Boolean = false,
    )

    companion object {
        private const val TAG = "LiveRelay"

        /** Coalesce cadence — last-value-wins. Only really exercised in the foreground; background
         *  GPS delivery is itself OS-throttled to roughly 1/min. */
        private const val MIN_INTERVAL_MS = 3_000L

        // Location preferences own the 0a03xx namespace; 0a0307 is the next free slot.
        private val STATE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0307")
    }
}
