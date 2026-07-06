package id.homebase.core.ui.screens.location.history

import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.ui.screens.location.model.LocationTrackHour
import kotlin.uuid.Uuid

/**
 * One device's day of movement, split into [segments] at tracking gaps so the
 * canvas never draws a line across a flight or an off-period.
 */
data class DeviceTrace(
    val deviceId: Uuid,
    /** Each segment is a time-ordered polyline; segments are non-overlapping. */
    val segments: List<List<BufferedLocationPoint>>,
) {
    val pointCount: Int get() = segments.sumOf { it.size }
}

data class DayStats(
    val distanceMeters: Double,
    /** First-to-last fix across all devices, epoch ms; null when empty. */
    val firstFixMs: Long?,
    val lastFixMs: Long?,
    val pointCount: Int,
    val deviceCount: Int,
)

/**
 * Pure assembly of decoded hour files (+ this device's unflushed buffer rows)
 * into per-device, gap-segmented traces for one local day. Kept free of DB and
 * clock dependencies so it is unit-testable.
 */
object LocationHistoryAssembler {

    const val GAP_MAX_INTERVAL_MS = 15 * 60_000L
    const val GAP_MAX_JUMP_METERS = 2_000.0

    fun assemble(
        hours: List<LocationTrackHour>,
        /** This device's not-yet-flushed buffer rows (today only); may be empty. */
        bufferPoints: List<BufferedLocationPoint>,
        bufferDeviceId: Uuid,
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<DeviceTrace> {
        val byDevice = mutableMapOf<Uuid, MutableMap<Long, BufferedLocationPoint>>()
        for (hour in hours) {
            val device = byDevice.getOrPut(hour.deviceId) { mutableMapOf() }
            for (p in hour.points) {
                if (p.t in dayStartMs until dayEndMs) device[p.t] = p
            }
        }
        // Buffer rows are authoritative for their timestamps (full precision vs
        // the header's quantized trace) — overwrite on collision.
        if (bufferPoints.isNotEmpty()) {
            val device = byDevice.getOrPut(bufferDeviceId) { mutableMapOf() }
            for (p in bufferPoints) {
                if (p.t in dayStartMs until dayEndMs) device[p.t] = p
            }
        }
        return byDevice.entries
            .sortedBy { it.key.toString() }
            .mapNotNull { (deviceId, pointsByT) ->
                if (pointsByT.isEmpty()) return@mapNotNull null
                DeviceTrace(deviceId, segment(pointsByT.values.sortedBy { it.t }))
            }
    }

    /**
     * Build a single subject's day from a flat, unsorted point list — e.g. a
     * family member's day pulled for the emergency feature. Points keep their
     * `steps`/`bat` through unchanged. Returns one gap-segmented [DeviceTrace]
     * (or empty when there are no points). For multi-device data, group by
     * device and call per group.
     */
    fun singleDeviceTraces(points: List<BufferedLocationPoint>, deviceId: Uuid): List<DeviceTrace> {
        if (points.isEmpty()) return emptyList()
        return listOf(DeviceTrace(deviceId, segment(points.sortedBy { it.t })))
    }

    fun stats(traces: List<DeviceTrace>): DayStats {
        var distance = 0.0
        var first: Long? = null
        var last: Long? = null
        for (trace in traces) {
            for (segment in trace.segments) {
                for (i in 1 until segment.size) {
                    distance += LocationPointStore.haversineMeters(
                        segment[i - 1].lat, segment[i - 1].lon,
                        segment[i].lat, segment[i].lon,
                    )
                }
                segment.firstOrNull()?.t?.let { t -> first = minOf(first ?: t, t) }
                segment.lastOrNull()?.t?.let { t -> last = maxOf(last ?: t, t) }
            }
        }
        return DayStats(
            distanceMeters = distance,
            firstFixMs = first,
            lastFixMs = last,
            pointCount = traces.sumOf { it.pointCount },
            deviceCount = traces.size,
        )
    }

    private fun segment(points: List<BufferedLocationPoint>): List<List<BufferedLocationPoint>> {
        if (points.isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<BufferedLocationPoint>>(mutableListOf(points.first()))
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val gap = cur.t - prev.t > GAP_MAX_INTERVAL_MS ||
                LocationPointStore.haversineMeters(prev.lat, prev.lon, cur.lat, cur.lon) > GAP_MAX_JUMP_METERS
            if (gap) segments.add(mutableListOf(cur)) else segments.last().add(cur)
        }
        return segments
    }
}
