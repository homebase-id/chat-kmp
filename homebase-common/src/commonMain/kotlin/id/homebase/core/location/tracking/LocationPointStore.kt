package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
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
) : LocationPointSink {

    private val logger = Logger.withTag("LocationPointStore")

    private val _lastPoint = MutableStateFlow<RawLocationPoint?>(null)
    val lastPoint: StateFlow<RawLocationPoint?> = _lastPoint.asStateFlow()

    override suspend fun submit(points: List<RawLocationPoint>) {
        if (points.isEmpty()) return
        val accepted = mutableListOf<RawLocationPoint>()
        var last = _lastPoint.value ?: databaseManager.locationPoint.selectLatest()?.toRaw()
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
        databaseManager.locationPoint.insertPoints(accepted.map { it.toBuffered() })
        _lastPoint.value = last
        logger.d { "Buffered ${accepted.size}/${points.size} points (last src=${last?.src})" }
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
