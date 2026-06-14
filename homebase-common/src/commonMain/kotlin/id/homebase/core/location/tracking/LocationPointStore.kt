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
 * The single funnel for captured GPS fixes: dedups against the last accepted
 * point and buffers survivors into the LocationPoint table, from which
 * `LocationTrackUploaderService` drains them into hour files on the drive.
 *
 * Both capture paths land here — the in-process tracker callbacks and the
 * Android background `LocationUpdatesReceiver` woken in a cold process.
 */
class LocationPointStore(
    private val databaseManager: DatabaseManager,
    private val deviceSensors: DeviceSensors,
) : LocationPointSink {

    private val logger = Logger.withTag("LocationPointStore")

    private val _lastPoint = MutableStateFlow<RawLocationPoint?>(null)
    val lastPoint: StateFlow<RawLocationPoint?> = _lastPoint.asStateFlow()

    override suspend fun submit(points: List<RawLocationPoint>) {
        if (points.isEmpty()) return
        val accepted = mutableListOf<RawLocationPoint>()
        val prevPoint = _lastPoint.value ?: databaseManager.locationPoint.selectLatest()?.toRaw()
        var last = prevPoint
        for (p in points.sortedBy { it.t }) {
            if (last != null && p.t <= last.t) continue
            // Thin stationary noise: skip fixes that moved < MIN_DISPLACEMENT_M
            // AND arrived < MIN_INTERVAL_MS after the last accepted one.
            if (last != null &&
                p.t - last.t < MIN_INTERVAL_MS &&
                haversineMeters(last.lat, last.lon, p.lat, p.lon) < MIN_DISPLACEMENT_M
            ) continue
            accepted += p
            last = p
        }
        if (accepted.isEmpty()) return

        // Device readings. Battery (free, permission-less) is stamped on every
        // point — the encoder surfaces only the hour's first, but any point may
        // be it. The step delta covers the window since the previous accepted
        // point and is attributed to the newest point of this batch (exact
        // per-point in foreground; a batch window's steps in background).
        val battery = runCatching { deviceSensors.batteryPercent() }
            .onFailure { logger.d(it) { "battery read failed" } }.getOrNull()
        val sample = runCatching { deviceSensors.stepsSince(prevPoint?.t, loadCumulative()) }
            .onFailure { logger.d(it) { "step read failed" } }.getOrNull()
        sample?.cumulative?.let { saveCumulative(it) }

        val lastIndex = accepted.lastIndex
        val buffered = accepted.mapIndexed { i, p ->
            p.toBuffered().copy(
                bat = battery,
                steps = if (i == lastIndex) sample?.deltaSteps else null,
            )
        }
        databaseManager.locationPoint.insertPoints(buffered)
        _lastPoint.value = last
        logger.d {
            "Buffered ${accepted.size}/${points.size} points " +
                "(last src=${last?.src} bat=$battery steps=${sample?.deltaSteps})"
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

    /** Rows still buffered on this device — the UI's "waiting to upload" count. */
    suspend fun countPendingUpload(): Long = databaseManager.locationPoint.countAll()

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
