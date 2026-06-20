package id.homebase.chat.services.livelocation

import co.touchlab.kermit.Logger
import id.homebase.api.client.liverelay.LIVE_LOCATION_CHANNEL_KEY
import id.homebase.api.client.liverelay.LiveLocationCodec
import id.homebase.api.client.liverelay.LiveLocationPoint
import id.homebase.api.client.liverelay.LiveRelayProvider
import id.homebase.api.client.liverelay.LiveShareRoster
import id.homebase.api.client.liverelay.TimedRecipient
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
 * Live-location sender for the Live Relay debug-flow build. While any recipient's share window is
 * live it relays the device's latest GPS fix to that recipient set over [LiveRelayProvider],
 * throttled to [MIN_INTERVAL_MS] (last-value-wins).
 *
 * **Recipient roster, not an on/off flag.** Recipients are kept as {identity, end-time} pairs
 * ([TimedRecipient]) so the multi-share case is correct: the same recipient added by two requests
 * collapses to one entry with the latest end-time, overlapping shares union, and a recipient drops
 * off automatically once their window passes — no manual stop required. End-times are sender-side
 * only; they are never sent over the wire (the relay stays ephemeral/last-value-wins). See
 * [LiveShareRoster].
 *
 * **Where the send fires:** [onGpsBuffered] is invoked from the `onPointsBuffered` seam in
 * `LocationPointStore.submit()` (wired in `AppModule`) — the only hook that runs on OS-delivered
 * points while the app is **backgrounded or cold-woken** (Android `LocationUpdatesReceiver`, iOS
 * `CLLocationManager` delegate). It deliberately does NOT collect `lastPoint` as a Flow: that
 * StateFlow is only observed by UI, so a collector would silently stop sending once backgrounded.
 *
 * **Persisted state:** the roster (with absolute end-times) is stored in keyValue so a
 * `BroadcastReceiver`-woken cold process (which rebuilds this singleton from scratch) still knows to
 * relay. The fixed [LIVE_LOCATION_CHANNEL_KEY] needs no persistence.
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

    /** True while at least one recipient's share window is still live. */
    fun isActive(): Boolean = LiveShareRoster.live(state.value.recipients, nowMs()).isNotEmpty()

    /**
     * Share live location with [recipients] for [durationMs] from now. Merges into any existing
     * roster (a recipient already present keeps the later end-time), so starting a second share
     * never drops the first's recipients.
     */
    suspend fun start(recipients: List<OdinId>, durationMs: Long = DEFAULT_DURATION_MS) {
        val now = nowMs()
        val roster = LiveShareRoster.add(
            current = state.value.recipients,
            add = recipients.map { it.domainName },
            endTimeMs = now + durationMs,
            nowMs = now,
        )
        // Ensure GPS is actually flowing. If the user's tracking master switch is off, the coordinator
        // never started the tracker — start it ourselves and remember to stop it when sharing ends.
        val startedByUs = state.value.startedTrackerByUs || ensureTrackerStarted()
        val next = LiveShareState(recipients = roster, startedTrackerByUs = startedByUs)
        update(next)
        logger.i {
            "START +${recipients.size} entries=${roster.size} " +
                "uniqueLive=${LiveShareRoster.liveRecipientIds(roster, now).size} " +
                "until=${now + durationMs} startedTracker=$startedByUs"
        }
    }

    /** Stop ALL live sharing now (manual stop). Per-conversation stop is a UX-plan concern. */
    suspend fun stop() {
        if (state.value.startedTrackerByUs) locationTracker.stop()
        update(LiveShareState())
        logger.i { "STOP" }
    }

    /**
     * Called from the GPS sink (`onPointsBuffered`) after each accepted batch — background-capable.
     * Prunes expired recipients, relays the just-accepted latest point to the live set if the
     * throttle window has elapsed. Never throws into the sink chain.
     */
    suspend fun onGpsBuffered() {
        val now = nowMs()
        val cur = state.value
        val live = LiveShareRoster.live(cur.recipients, now)
        // Roster shrank (something expired) — persist the pruned set; if nothing's left and we started
        // the tracker, stop it so GPS doesn't stay on forever.
        if (live.size != cur.recipients.size) {
            val keepStarted = live.isNotEmpty() && cur.startedTrackerByUs
            if (live.isEmpty() && cur.startedTrackerByUs) locationTracker.stop()
            update(LiveShareState(recipients = live, startedTrackerByUs = keepStarted))
        }
        if (live.isEmpty()) return
        if (now - lastSentMs < MIN_INTERVAL_MS) return
        // Skip (don't queue) if a send is already in flight — last-value-wins, the next batch supersedes.
        if (!sendLock.tryLock()) return
        try {
            val t = nowMs()
            if (t - lastSentMs < MIN_INTERVAL_MS) return
            // Send to UNIQUE identities — never the same coordinate to the same identity twice, even
            // if several share entries name them.
            val recipientIds = LiveShareRoster.liveRecipientIds(state.value.recipients, t)
            if (recipientIds.isEmpty()) return
            // Read the just-set latest point synchronously (valid in background — submit() sets
            // _lastPoint.value on the line before it calls onPointsBuffered; we read .value, we do
            // NOT collect the Flow).
            val p = locationPointStore.lastPoint.value ?: return
            val blob = LiveLocationCodec.encode(
                LiveLocationPoint(lat = p.lat, lon = p.lon, acc = p.acc, spd = p.spd, hdg = p.hdg, ts = p.t)
            )
            runCatching { liveRelayProvider.relay(LIVE_LOCATION_CHANNEL_KEY, recipientIds, blob) }
                .onFailure { logger.w(it) { "relay failed" } }
            lastSentMs = t
        } finally {
            sendLock.unlock()
        }
    }

    /** Clear in-memory + persisted state on logout. */
    fun reset() {
        if (state.value.startedTrackerByUs) locationTracker.stop()
        state.value = LiveShareState()
        lastSentMs = 0L
        scope.launch { persist(LiveShareState()) }
    }

    private fun ensureTrackerStarted(): Boolean =
        if (!locationPreferences.trackingEnabled.value && locationTracker.isAvailable) {
            locationTracker.start(TrackingMode.Foreground)
            true
        } else {
            false
        }

    private suspend fun update(next: LiveShareState) {
        state.value = next
        persist(next)
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
        val recipients: List<TimedRecipient> = emptyList(),
        val startedTrackerByUs: Boolean = false,
    )

    companion object {
        private const val TAG = "LiveRelay"

        /** Coalesce cadence — last-value-wins. Only really exercised in the foreground; background
         *  GPS delivery is itself OS-throttled to roughly 1/min. */
        private const val MIN_INTERVAL_MS = 3_000L

        /** Default share window for the debug toggle (no duration UI yet). */
        private const val DEFAULT_DURATION_MS = 60 * 60 * 1000L // 1 hour

        // Location preferences own the 0a03xx namespace; 0a0307 is the next free slot.
        private val STATE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0307")
    }
}
