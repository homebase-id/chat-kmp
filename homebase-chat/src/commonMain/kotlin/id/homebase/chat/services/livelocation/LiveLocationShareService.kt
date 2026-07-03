package id.homebase.chat.services.livelocation

import co.touchlab.kermit.Logger
import id.homebase.api.client.liverelay.LIVE_LOCATION_CHANNEL_KEY
import id.homebase.api.client.liverelay.LiveLocationCodec
import id.homebase.api.client.liverelay.LiveLocationPoint
import id.homebase.api.client.liverelay.LiveShareRoster
import id.homebase.api.client.liverelay.TimedRecipient
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.location.tracking.RawLocationPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Live-location sender for the Live Relay debug-flow build. While any recipient's share window is
 * live it relays the device's latest GPS fix to that recipient set over [LiveRelayProvider],
 * throttled to [MIN_INTERVAL_MS] (last-value-wins).
 *
 * **Recipient roster, not an on/off flag.** Recipients are kept as {identity, end-time} pairs
 * ([TimedRecipient]) — one entry per share action, duplicates allowed — so the multi-share case is
 * correct: the same recipient added by two requests stays as two individually-removable entries,
 * overlapping shares union, send dedups to unique identities, and a recipient drops off once its
 * window passes. End-times are sender-side only; never sent over the wire. See [LiveShareRoster].
 *
 * **It does not own the GPS tracker.** Capturing GPS is owned solely by `LocationTrackingCoordinator`
 * (the single arbiter of tracker start/stop/mode). This service only declares *whether* it needs GPS:
 * [hasLiveShare] is read by the coordinator's `liveShareActive` predicate, and [onLiveShareChanged]
 * pokes the coordinator to re-evaluate when a share starts/stops/expires. That keeps mode selection
 * (Foreground/Background) and the cold-start re-arm in one place — including the iOS
 * significant-location-change relaunch, so a share survives a full process kill.
 *
 * **Where the send fires:** [relayLatest] is invoked by `LocationFixRouter` for every accepted fix —
 * the routing seam that runs on OS-delivered points while the app is **backgrounded or cold-woken**
 * (Android `LocationUpdatesReceiver`, iOS `CLLocationManager` delegate). The router hands it the point
 * directly, so it deliberately does NOT collect `lastPoint` as a Flow: that StateFlow is only observed
 * by UI, so a collector would silently stop sending once backgrounded.
 *
 * **Persisted state:** the roster (with absolute end-times) is stored in keyValue so a cold process
 * (rebuilt from scratch) still knows to relay, and a share lives until its end-time / explicit [stop]
 * / logout — never cleared by a mere app open. Ephemeral by design — no drive writes, no outbox.
 */
class LiveLocationShareService(
    /** Relay seam: app→server live-relay POST. Wired to `LiveRelayProvider::relay`; a plain function
     *  so the share logic is testable without an HttpClient. Returns true on a 2xx. */
    private val relay: suspend (channelKey: String, recipients: List<String>, blob: String) -> Boolean,
    private val locationPointStore: LocationPointStore,
    private val databaseManager: DatabaseManager,
    /** Wired in AppModule to LocationTrackingCoordinator.refreshGpsHold(). */
    private val onLiveShareChanged: () -> Unit = {},
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val logger = Logger.withTag(TAG)

    // App-lifetime singleton scope, used only for the fire-and-forget disk cleanup in [reset]
    // (persist is suspend; reset is called from the non-suspend onPostAuthenticated bootstrap).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val state = MutableStateFlow(readPersisted())

    /**
     * The current share roster (all entries, including not-yet-expired ones) for UI to observe —
     * e.g. the Location Dashboard's "Sharing with" list, which dedups by identity and shows the
     * longest end-time. Read-only; mutate only through [start]/[stop]/[stopAll].
     */
    val recipients: StateFlow<List<TimedRecipient>> =
        state.map { it.recipients }.stateIn(scope, SharingStarted.Eagerly, state.value.recipients)

    // Authoritative throttle bookkeeping — only touched under [sendLock].
    private val sendLock = Mutex()
    private var lastSentMs = 0L

    /** True while at least one recipient's share window is still live. Read by the coordinator. */
    fun hasLiveShare(): Boolean = LiveShareRoster.live(state.value.recipients, nowMs()).isNotEmpty()

    fun isActive(): Boolean = hasLiveShare()

    /**
     * Share live location with [recipients] until the absolute [endTimeMs] (UTC epoch-ms). Appends to
     * the roster (each share is a distinct entry), so starting a second share never drops the first's
     * recipients. The caller MUST pass the SAME absolute end-time it wrote onto the message descriptor
     * — that `{recipient, end-time}` identity is what lets [stop] later remove exactly this share.
     *
     * Immediately relays the current GPS fix (if any) to [recipients] so the server's retained
     * last-point is fresh from the first second — a recipient opening the map mid-share is hydrated by
     * the server with that point without waiting for the next OS fix.
     */
    suspend fun start(recipients: List<OdinId>, endTimeMs: Long) {
        val now = nowMs()
        val recipientIds = recipients.map { it.domainName }
        val roster = LiveShareRoster.add(
            current = state.value.recipients,
            add = recipientIds,
            endTimeMs = endTimeMs,
            nowMs = now,
        )
        update(LiveShareState(roster))
        onLiveShareChanged() // coordinator arms GPS for the share
        logger.i {
            "START +${recipients.size} entries=${roster.size} " +
                "uniqueLive=${LiveShareRoster.liveRecipientIds(roster, now).size} until=$endTimeMs"
        }
        pushCurrentPointNow(recipientIds)
    }

    /**
     * Stop exactly the share identified by `{[recipients], [endTimeMs]}` — the same absolute end-time
     * the matching chat bubble stored. Other live shares (to other people, or to the same people with a
     * different end-time) are left running. No-op if nothing matches.
     */
    suspend fun stop(recipients: List<OdinId>, endTimeMs: Long) {
        val roster = LiveShareRoster.remove(
            current = state.value.recipients,
            recipients = recipients.map { it.domainName },
            endTimeMs = endTimeMs,
        )
        update(LiveShareState(roster))
        onLiveShareChanged() // coordinator stops GPS if nothing else needs it
        logger.i { "STOP -${recipients.size} until=$endTimeMs entries=${roster.size}" }
    }

    /** Stop ALL live sharing now. The Dashboard's "stop sharing with everyone" and logout/reset. */
    suspend fun stopAll() {
        update(LiveShareState())
        onLiveShareChanged() // coordinator stops GPS if nothing else needs it
        logger.i { "STOP ALL" }
    }

    /**
     * Stop **every** live share to any of [recipients] — all their roster entries, regardless of
     * end-time. This is the Dashboard's per-person "stop sharing with this person" (the list is
     * deduped to one row per identity), distinct from [stop] which targets a single
     * `{recipient, end-time}` share (the per-bubble stop). No-op on an empty list.
     */
    suspend fun stopAll(recipients: List<OdinId>) {
        if (recipients.isEmpty()) return
        val roster = LiveShareRoster.removeRecipients(
            current = state.value.recipients,
            recipients = recipients.map { it.domainName },
        )
        update(LiveShareState(roster))
        onLiveShareChanged() // coordinator stops GPS if nothing else needs it
        logger.i { "STOP recipients=${recipients.size} entries=${roster.size}" }
    }

    /** Stop all of one person's live shares. Convenience over [stopAll]`(List<OdinId>)`. */
    suspend fun stopAll(odinId: OdinId) = stopAll(listOf(odinId))

    /**
     * Relay the latest GPS fix to [recipientIds] right now, ignoring the throttle window — used on
     * [start] so the just-added recipients (and the server's retained point) get a position without
     * waiting for the next OS fix. No-op if no fix is available yet (cold GPS) or the set is empty.
     */
    private suspend fun pushCurrentPointNow(recipientIds: List<String>) {
        if (recipientIds.isEmpty()) return
        val p = locationPointStore.lastPoint.value ?: return
        sendLock.withLock {
            val blob = LiveLocationCodec.encode(
                LiveLocationPoint(lat = p.lat, lon = p.lon, acc = p.acc, spd = p.spd, hdg = p.hdg, ts = p.t)
            )
            val ok = runCatching { relay(LIVE_LOCATION_CHANNEL_KEY, recipientIds.distinct(), blob) }
                .onFailure { logger.w(it) { "immediate push failed" } }
                .getOrDefault(false)
            logger.i { "immediate push ok=$ok to=${recipientIds.distinct().size}" }
            lastSentMs = nowMs()
        }
    }

    /**
     * Called by `LocationFixRouter` for each accepted fix [point] — background-capable. Self-gating:
     * prunes expired recipients (dropping the coordinator's GPS hold when the last share ends), then
     * relays [point] to the live set if the throttle window has elapsed. No-op when nothing is live.
     * Never throws into the routing chain.
     */
    suspend fun relayLatest(point: RawLocationPoint) {
        val now = nowMs()
        val cur = state.value
        val live = LiveShareRoster.live(cur.recipients, now)
        // Roster shrank (something expired) — persist the pruned set; if it fully emptied, let the
        // coordinator drop the GPS hold.
        if (live.size != cur.recipients.size) {
            update(LiveShareState(live))
            if (live.isEmpty()) onLiveShareChanged()
        }
        if (live.isEmpty()) return
        if (now - lastSentMs < MIN_INTERVAL_MS) {
            logger.i { "relayLatest throttle-skip sinceLastMs=${now - lastSentMs} live=${live.size} fg=${point.fg}" }
            return
        }
        // Skip (don't queue) if a send is already in flight — last-value-wins, the next batch supersedes.
        if (!sendLock.tryLock()) {
            logger.i { "relayLatest skip — send already in flight" }
            return
        }
        try {
            val t = nowMs()
            if (t - lastSentMs < MIN_INTERVAL_MS) return
            // Send to UNIQUE identities — never the same coordinate to the same identity twice, even
            // if several share entries name them.
            val recipientIds = LiveShareRoster.liveRecipientIds(state.value.recipients, t)
            if (recipientIds.isEmpty()) return
            val blob = LiveLocationCodec.encode(
                LiveLocationPoint(
                    lat = point.lat, lon = point.lon, acc = point.acc,
                    spd = point.spd, hdg = point.hdg, ts = point.t,
                )
            )
            val ok = runCatching { relay(LIVE_LOCATION_CHANNEL_KEY, recipientIds, blob) }
                .onFailure { logger.w(it) { "relay failed" } }
                .getOrDefault(false)
            logger.i { "relay SENT ok=$ok to=${recipientIds.size} fg=${point.fg} t=${point.t}" }
            lastSentMs = t
        } finally {
            sendLock.unlock()
        }
    }

    /**
     * Re-seed in-memory state from the current identity's DB and poke the coordinator to re-evaluate
     * its GPS hold. Called from `onPostAuthenticated` (the per-identity foreground bootstrap, mirroring
     * `LocationPreferences.reset`).
     *
     * It must **not** stop an ongoing share — a share lives until its end-time or an explicit [stop],
     * regardless of how the app was started or opened (cold start, manual reopen, background wake), so
     * a 2-week share just keeps going. After a logout the keyValue DB is wiped, so the read returns an
     * empty roster and [onLiveShareChanged] lets the coordinator drop the hold.
     *
     * Also **prunes expired entries off disk**. The only other pruning happens in [relayLatest],
     * which runs solely while GPS is delivering points (i.e. while a share is live). So a share that
     * expired (or was stopped) while the process wasn't running leaves dead `{recipient, end-time}`
     * entries in the persisted roster — harmless to behaviour ([live]/[hasLiveShare] filter by now —
     * but they accumulate on disk across shares. Dropping them here on every bootstrap keeps the
     * persisted roster from growing without bound.
     */
    fun reset() {
        val loaded = readPersisted()
        val live = LiveShareRoster.live(loaded.recipients, nowMs())
        state.value = LiveShareState(live)
        lastSentMs = 0L
        // Persist back only if load contained expired entries — clears stale roster data off disk.
        if (live.size != loaded.recipients.size) {
            logger.i { "reset pruned ${loaded.recipients.size - live.size} expired roster entries from disk" }
            scope.launch { persist(state.value) }
        }
        onLiveShareChanged()
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
