package id.homebase.core.ui.screens.location.history

import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.core.location.tracking.LocationPointStore
import kotlin.math.sqrt

private const val DWELL_MIN_R_DP = 7f
private const val DWELL_MAX_R_DP = 22f
private const val DWELL_SATURATE_MS = 4 * 60 * 60_000L // 4h — longer stays all render at max

/**
 * Screen-space dwell-dot radius (dp, not map-scaled, so zoom never bloats it): grows
 * on a saturating sqrt curve from [DWELL_MIN_R_DP] at the 15-minute floor to
 * [DWELL_MAX_R_DP], maxing out at [DWELL_SATURATE_MS] — so a 24-hour stay is the same
 * capped size as a 4-hour one, never absurd. Pure (testable); the canvas converts to px.
 */
fun dwellRadiusDp(durationMs: Long): Float {
    val span = (DWELL_SATURATE_MS - DayPlayback.DWELL_MIN_MS).toDouble()
    val f = ((durationMs - DayPlayback.DWELL_MIN_MS).toDouble() / span).coerceIn(0.0, 1.0)
    return DWELL_MIN_R_DP + (DWELL_MAX_R_DP - DWELL_MIN_R_DP) * sqrt(f).toFloat()
}

/**
 * A place the user lingered: a cluster of consecutive fixes that stayed within
 * [DWELL_RADIUS_M] of a running centroid for at least [DWELL_MIN_MS]. [lat]/[lon]
 * are the cluster centroid; [durationMs] drives the dwell-dot size.
 */
data class DwellStop(
    val deviceIndex: Int,
    val lat: Double,
    val lon: Double,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Time-warped playback model for one day's traces. The single animated quantity
 * is `clock` (an epoch-ms instant into the day): [clockAtProgress] maps a uniform
 * playback progress 0..1 onto real time so that **movement is slow and idle is
 * fast** — an 8-hour stay collapses to one [IDLE_BEAT_MS] beat instead of a third
 * of the runtime. Everything on the canvas then renders as "the day as of clock",
 * which also makes multi-device days correct and lets the scrubber set clock directly.
 *
 * Built once per loaded day off the main thread (pure, O(points)); see [build].
 */
class DayPlayback internal constructor(
    val firstFixMs: Long,
    val lastFixMs: Long,
    val totalAnimMillis: Int,
    val stops: List<DwellStop>,
    // Parallel warp tables (size = event count): real epoch ms at each event, and the
    // cumulative animation ms when the warped playhead reaches it. animCumMs is
    // non-decreasing, animCumMs[0] = 0, animCumMs.last() = totalAnimMillis.
    private val realAtStep: LongArray,
    private val animCumMs: LongArray,
) {
    /** Warped: a uniform progress 0..1 → real epoch ms (lerped within the active step). */
    fun clockAtProgress(progress: Float): Long {
        if (realAtStep.size == 1) return realAtStep[0]
        val target = (progress.coerceIn(0f, 1f).toDouble() * totalAnimMillis).toLong()
        // Largest i with animCumMs[i] <= target.
        var lo = 0
        var hi = animCumMs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (animCumMs[mid] <= target) lo = mid else hi = mid - 1
        }
        if (lo >= animCumMs.size - 1) return realAtStep.last()
        val span = animCumMs[lo + 1] - animCumMs[lo]
        val frac = if (span > 0) (target - animCumMs[lo]).toDouble() / span else 0.0
        return realAtStep[lo] + ((realAtStep[lo + 1] - realAtStep[lo]) * frac).toLong()
    }

    companion object {
        const val DWELL_RADIUS_M = 75.0
        const val DWELL_MIN_MS = 15 * 60_000L
        private const val MOVE_EPS_M = 30.0
        private const val IDLE_BEAT_MS = 450L
        private const val MOVING_BUDGET_MS = 9_000.0
        private const val MOVING_STEP_CAP_MS = 600.0
        private const val MIN_ANIM_MS = 5_000L
        private const val MAX_ANIM_MS = 18_000L

        /** Returns null when the day has no fixes. */
        fun build(traces: List<DeviceTrace>): DayPlayback? {
            val events = buildList {
                traces.forEachIndexed { di, trace ->
                    trace.segments.forEachIndexed { si, segment ->
                        segment.forEach { p -> add(Event(p.t, p.lat, p.lon, di, si)) }
                    }
                }
            }.sortedBy { it.t }
            if (events.isEmpty()) return null

            val stops = detectDwells(traces)
            if (events.size == 1) {
                val t = events[0].t
                return DayPlayback(t, t, MIN_ANIM_MS.toInt(), stops, longArrayOf(t), longArrayOf(0L))
            }

            val n = events.size - 1
            val moving = BooleanArray(n)
            val movedM = DoubleArray(n)
            for (k in 0 until n) {
                val a = events[k]
                val b = events[k + 1]
                val sameLeg = a.deviceIndex == b.deviceIndex && a.segIndex == b.segIndex
                val moved = if (sameLeg) {
                    LocationPointStore.haversineMeters(a.lat, a.lon, b.lat, b.lon)
                } else 0.0
                moving[k] = sameLeg && moved > MOVE_EPS_M
                if (moving[k]) movedM[k] = moved
            }

            // Moving steps: anim time ∝ distance (constant screen-speed trail), each
            // capped so a single sparse/teleport step can't dominate the budget.
            val animStep = DoubleArray(n)
            val totalMoved = movedM.sum()
            if (totalMoved > 0) {
                for (k in 0 until n) if (moving[k]) {
                    animStep[k] = (movedM[k] / totalMoved * MOVING_BUDGET_MS)
                        .coerceAtMost(MOVING_STEP_CAP_MS)
                }
            }
            // Each maximal run of idle steps collapses to a single beat.
            var k = 0
            while (k < n) {
                if (!moving[k]) {
                    val runStart = k
                    while (k < n && !moving[k]) k++
                    animStep[runStart] = IDLE_BEAT_MS.toDouble()
                } else k++
            }

            val rawTotal = animStep.sum().coerceAtLeast(1.0)
            val total = rawTotal.toLong().coerceIn(MIN_ANIM_MS, MAX_ANIM_MS)
            val scale = total / rawTotal

            val real = LongArray(events.size)
            val animCum = LongArray(events.size)
            real[0] = events[0].t
            var acc = 0.0
            for (i in 0 until n) {
                acc += animStep[i] * scale
                real[i + 1] = events[i + 1].t
                animCum[i + 1] = acc.toLong()
            }
            animCum[animCum.size - 1] = total // pin the end exactly

            return DayPlayback(events.first().t, events.last().t, total.toInt(), stops, real, animCum)
        }

        private fun detectDwells(traces: List<DeviceTrace>): List<DwellStop> {
            val out = mutableListOf<DwellStop>()
            traces.forEachIndexed { di, trace ->
                // Segments are time-ordered and non-overlapping, so flattening keeps
                // chronological order; clustering across a segment break lets a stay
                // that was split by a tracking gap still register as one dwell.
                val pts = trace.segments.flatten()
                var i = 0
                while (i < pts.size) {
                    var sumLat = pts[i].lat
                    var sumLon = pts[i].lon
                    var cLat = pts[i].lat
                    var cLon = pts[i].lon
                    var count = 1
                    val startT = pts[i].t
                    var endT = pts[i].t
                    var j = i + 1
                    while (j < pts.size &&
                        LocationPointStore.haversineMeters(cLat, cLon, pts[j].lat, pts[j].lon) <= DWELL_RADIUS_M
                    ) {
                        sumLat += pts[j].lat
                        sumLon += pts[j].lon
                        count++
                        cLat = sumLat / count
                        cLon = sumLon / count
                        endT = pts[j].t
                        j++
                    }
                    if (endT - startT >= DWELL_MIN_MS) {
                        out.add(DwellStop(di, cLat, cLon, startT, endT))
                        i = j
                    } else {
                        i++
                    }
                }
            }
            return out
        }
    }

    private data class Event(
        val t: Long,
        val lat: Double,
        val lon: Double,
        val deviceIndex: Int,
        val segIndex: Int,
    )
}
