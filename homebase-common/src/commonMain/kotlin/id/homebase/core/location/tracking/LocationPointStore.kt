package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
import id.homebase.api.storage.SharedPreferences
import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thin state + history primitives for captured GPS fixes. It no longer decides what a fix *does* —
 * that routing (persist-as-history vs relay-as-live) lives in [LocationFixRouter], the single seam
 * every capture path submits to. This class only provides the pieces the router orchestrates:
 *
 *  - [dedup] — drop fixes older than the last accepted one + a stationary-noise filter (pure read).
 *  - [lastPoint] / [publishLastKnown] — the latest known position, updated for EVERY accepted fix
 *    (history on or off) so the live map and `getCurrentGps` always see a current dot.
 *  - [persistHistory] — stamp battery/steps and insert into the LocationPoint table (history only),
 *    from which `LocationTrackUploaderService` drains them into hour files on the drive.
 *
 * ### Pipeline cadences (one place, see #846)
 * Five independent knobs shape the foreground capture→submit→route flow; documented here so they're
 * greppable and don't silently drift:
 *  - OS acquisition: the common per-profile table [TrackingProfile.spec] (15 s / 10 m live-fg,
 *    30 s / 25 m history-fg, coarse bg) — both platform trackers translate it.
 *  - Store stationary-noise filter: [MIN_DISPLACEMENT_M] (8 m) AND [MIN_INTERVAL_MS] (20 s) — here.
 *  - Foreground flush ticker: 120 s — `LocationTrackingCoordinator.startTicker`.
 *  - History flush rate-gate: 60 s — `LocationTrackUploaderService`.
 *  - Live relay throttle: 3 s, last-value-wins — `LiveLocationShareService`.
 */
class LocationPointStore(
    private val databaseManager: DatabaseManager,
    private val deviceSensors: DeviceSensors,
) {

    private val logger = Logger.withTag("LocationPointStore")

    private val _lastPoint = MutableStateFlow<RawLocationPoint?>(null)
    val lastPoint: StateFlow<RawLocationPoint?> = _lastPoint.asStateFlow()

    /**
     * Filter a freshly-captured batch against the last accepted fix: drop out-of-order points
     * (`p.t <= last.t`) and stationary noise ([isStationaryNoise]). Pure read — no writes, no
     * `lastPoint` mutation, no sensor reads. Baseline is the in-memory [lastPoint] or, on a cold
     * process, the latest persisted row. Returns the accepted points in time order (possibly empty).
     */
    suspend fun dedup(points: List<RawLocationPoint>): List<RawLocationPoint> {
        if (points.isEmpty()) return emptyList()
        val accepted = mutableListOf<RawLocationPoint>()
        var last = _lastPoint.value ?: databaseManager.locationPoint.selectLatest()?.toRaw()
        for (p in points.sortedBy { it.t }) {
            if (last != null && p.t <= last.t) continue
            if (last != null && isStationaryNoise(last, p)) continue
            accepted += p
            last = p
        }
        return accepted
    }

    /**
     * A fix closer than [MIN_DISPLACEMENT_M] in space AND [MIN_INTERVAL_MS] in time to the previous
     * accepted fix is stationary noise — drop it before it costs DB and upload bytes.
     *
     * Deliberately defensive over the OS-level displacement filter (#846 Q3): it is the **single**
     * gate every capture path funnels through — the Android foreground overlay (10 m), the iOS
     * distance filter (which drifts and re-fires near the threshold), the background PendingIntent
     * (25 m), **and** one-shot `getCurrentGps` fixes (no OS displacement filter at all). Enforcing the
     * 8 m∧20 s rule here guarantees consistent thinning regardless of which source produced the fix.
     */
    private fun isStationaryNoise(prev: RawLocationPoint, p: RawLocationPoint): Boolean =
        p.t - prev.t < MIN_INTERVAL_MS &&
            haversineMeters(prev.lat, prev.lon, p.lat, p.lon) < MIN_DISPLACEMENT_M

    /** Update the latest-known position. Called for every accepted fix, regardless of history/relay. */
    fun publishLastKnown(point: RawLocationPoint) {
        _lastPoint.value = point
    }

    /**
     * Persist [points] as the user's location history: stamp device readings and insert into the
     * LocationPoint table. Called by [LocationFixRouter] only when history is allowed.
     *
     * Battery (free, permission-less) is stamped on every point — the encoder surfaces only the
     * hour's first, but any point may be it. The step delta covers the window since the previous
     * persisted point and is attributed to the newest point of this batch.
     */
    suspend fun persistHistory(points: List<RawLocationPoint>) {
        if (points.isEmpty()) return
        // Steps baseline = last PERSISTED point's time. Reads before the insert below, so it is the
        // pre-batch row (best-effort across a history-off gap; steps are non-critical).
        val prevPersistedT = databaseManager.locationPoint.selectLatest()?.toRaw()?.t
        val battery = runCatching { deviceSensors.batteryPercent() }
            .onFailure { logger.d(it) { "battery read failed" } }.getOrNull()
        val sample = runCatching { deviceSensors.stepsSince(prevPersistedT, loadCumulative()) }
            .onFailure { logger.d(it) { "step read failed" } }.getOrNull()
        sample?.cumulative?.let { saveCumulative(it) }

        val lastIndex = points.lastIndex
        val buffered = points.mapIndexed { i, p ->
            p.toBuffered().copy(
                bat = battery,
                steps = if (i == lastIndex) sample?.deltaSteps else null,
            )
        }
        databaseManager.locationPoint.insertPoints(buffered)
        val pending = runCatching { databaseManager.locationPoint.countUnmarked() }.getOrDefault(-1L)
        logger.i {
            "Persisted ${points.size} history points pending=$pending " +
                "(bat=$battery steps=${sample?.deltaSteps})"
        }
    }

    private fun loadCumulative(): Long? = runCatching {
        if (SharedPreferences.contains(STEP_CUMULATIVE_KEY)) {
            SharedPreferences.getLong(STEP_CUMULATIVE_KEY)
        } else null
    }.getOrNull()

    private fun saveCumulative(value: Long) {
        runCatching { SharedPreferences.putLong(STEP_CUMULATIVE_KEY, value) }
    }

    /**
     * Rows not yet part of an enqueued hour file — the UI's "waiting to upload"
     * count. Marked rows are already uploaded (kept only until the hour closes),
     * so they must not count here.
     */
    suspend fun countPendingUpload(): Long = databaseManager.locationPoint.countUnmarked()

    suspend fun countSince(fromInclusiveMs: Long): Long =
        databaseManager.locationPoint.countSince(fromInclusiveMs)

    /** Re-seed [lastPoint] from the DB (e.g. on screen entry). */
    suspend fun refresh() {
        _lastPoint.value = databaseManager.locationPoint.selectLatest()?.toRaw()
    }

    /**
     * Clear in-memory state for a clean login. Called from `onPostAuthenticated`
     * in `AppModule.kt`; the table itself is wiped with the rest of the DB.
     */
    fun reset() {
        _lastPoint.value = null
    }

    private fun RawLocationPoint.toBuffered() = BufferedLocationPoint(
        t = t, lat = lat, lon = lon, acc = acc, alt = alt, altAcc = altAcc,
        spd = spd, hdg = hdg, src = src, fg = fg,
    )

    private fun BufferedLocationPoint.toRaw() = RawLocationPoint(
        t = t, lat = lat, lon = lon, acc = acc, alt = alt, altAcc = altAcc,
        spd = spd, hdg = hdg, src = src, fg = fg,
    )

    companion object {
        // A fix closer than this in BOTH space and time to the previous accepted
        // fix is stationary noise — drop it before it costs DB and upload bytes.
        const val MIN_DISPLACEMENT_M = 8.0
        const val MIN_INTERVAL_MS = 20_000L

        // Persisted (survives process death like the device id) so the Android
        // background receiver can compute a step delta after a cold wake.
        private const val STEP_CUMULATIVE_KEY = "location_last_step_cumulative"

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * PI / 180.0
            val dLon = (lon2 - lon1) * PI / 180.0
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                sin(dLon / 2) * sin(dLon / 2)
            return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
