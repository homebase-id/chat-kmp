package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger

/**
 * THE single home of the "what does a captured GPS fix do?" decision (issue #835). Every capture
 * path — the continuous platform tracker, the Android background `LocationUpdatesReceiver`, and the
 * one-shot `getCurrentGps` (via `LocationService`) — submits here, so the persist-vs-relay routing
 * is greppable in one place instead of smeared across the store, the share service, and DI wiring.
 *
 * The routing tree, for each accepted (deduped) fix:
 *  1. **Always** publish it as the latest-known position ([LocationPointStore.publishLastKnown]) —
 *     feeds the live map's "you" dot and `getCurrentGps`, even with history off and no share.
 *  2. **Persist as history iff** [allowHistory] — the only flag this class reads (bug #823: a live
 *     share holds GPS on with history off, but those fixes must NOT be recorded as history).
 *  3. **Hand every fix to the live-share service** ([relayLatest]); it self-gates — relays to live
 *     recipients under its throttle, prunes expired roster entries, and drops the coordinator's GPS
 *     hold when the last share ends. The roster check stays encapsulated there (its own state), so
 *     this class doesn't duplicate it.
 *
 * Dedup (out-of-order + stationary-noise) is delegated to [LocationPointStore.dedup]; this class owns
 * only the routing. It is the [LocationPointSink] the platform trackers are created with.
 */
class LocationFixRouter(
    private val store: LocationPointStore,
    private val allowHistory: () -> Boolean,
    private val persistAsHistory: suspend (List<RawLocationPoint>) -> Unit,
    private val relayLatest: suspend (RawLocationPoint) -> Unit,
) : LocationPointSink {

    private val logger = Logger.withTag("LocationFixRouter")

    override suspend fun submit(points: List<RawLocationPoint>) {
        val accepted = store.dedup(points)
        if (accepted.isEmpty()) {
            if (points.isNotEmpty()) {
                logger.d { "Dropped all ${points.size} fix(es) (out-of-order / stationary-noise)" }
            }
            return
        }
        val latest = accepted.last()

        // 1. Latest-known — always.
        store.publishLastKnown(latest)

        // 2. History — only when allowed.
        val persisted = allowHistory()
        if (persisted) {
            runCatching { persistAsHistory(accepted) }
                .onFailure { logger.e(it) { "persistAsHistory failed" } }
        }

        // 3. Live relay — service self-gates on its roster; also prunes + drops the GPS hold.
        runCatching { relayLatest(latest) }
            .onFailure { logger.w(it) { "relayLatest failed" } }

        logger.i {
            val verb = if (persisted) "Routed" else "Routed (history off)"
            "$verb ${accepted.size}/${points.size} fixes (last src=${latest.src})"
        }
    }
}
