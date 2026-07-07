package id.homebase.core.ui.screens.location.history

import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.core.ui.screens.location.model.LocationTrackHour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LocationHistoryAssemblerTest {

    private val deviceA = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val deviceB = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002")
    private val dayStart = 1_765_324_800_000L
    private val dayEnd = dayStart + 24 * 3_600_000L

    private fun point(t: Long, lat: Double = 52.0, lon: Double = 13.0) =
        BufferedLocationPoint(t = t, lat = lat, lon = lon, acc = 10.0, src = "gps", fg = true)

    private fun hour(deviceId: Uuid, points: List<BufferedLocationPoint>) =
        LocationTrackHour(
            deviceId = deviceId,
            hourStartMs = (points.first().t / 3_600_000L) * 3_600_000L,
            points = points,
            fullCount = points.size,
        )

    @Test
    fun groupsByDeviceAndSortsByTime() {
        val traces = LocationHistoryAssembler.assemble(
            hours = listOf(
                hour(deviceA, listOf(point(dayStart + 2000), point(dayStart + 1000))),
                hour(deviceB, listOf(point(dayStart + 500))),
            ),
            bufferPoints = emptyList(),
            bufferDeviceId = deviceA,
            dayStartMs = dayStart,
            dayEndMs = dayEnd,
        )
        assertEquals(2, traces.size)
        val a = traces.first { it.deviceId == deviceA }
        assertEquals(listOf(dayStart + 1000, dayStart + 2000), a.segments.single().map { it.t })
    }

    @Test
    fun filtersPointsOutsideTheDay() {
        // An hour file straddling the local-day boundary delivers points from
        // both sides; only the in-day ones survive.
        val traces = LocationHistoryAssembler.assemble(
            hours = listOf(
                hour(deviceA, listOf(point(dayStart - 1), point(dayStart), point(dayEnd - 1), point(dayEnd))),
            ),
            bufferPoints = emptyList(),
            bufferDeviceId = deviceA,
            dayStartMs = dayStart,
            dayEndMs = dayEnd,
        )
        // The two survivors are ~24h apart, so they also land in separate segments.
        assertEquals(
            listOf(dayStart, dayEnd - 1),
            traces.single().segments.flatten().map { it.t },
        )
    }

    @Test
    fun bufferPointsMergeAndOverrideHeaderDuplicates() {
        val headerPoint = point(dayStart + 1000, lat = 52.00001) // quantized header copy
        val bufferPoint = point(dayStart + 1000, lat = 52.000012345) // full precision
        val traces = LocationHistoryAssembler.assemble(
            hours = listOf(hour(deviceA, listOf(headerPoint))),
            bufferPoints = listOf(bufferPoint, point(dayStart + 2000)),
            bufferDeviceId = deviceA,
            dayStartMs = dayStart,
            dayEndMs = dayEnd,
        )
        val segment = traces.single().segments.single()
        assertEquals(2, segment.size)
        assertEquals(52.000012345, segment.first().lat)
    }

    @Test
    fun splitsSegmentsOnTimeGap() {
        val traces = LocationHistoryAssembler.assemble(
            hours = listOf(
                hour(
                    deviceA,
                    listOf(
                        point(dayStart),
                        point(dayStart + 60_000),
                        // 20-minute hole — tracking was off.
                        point(dayStart + 60_000 + 20 * 60_000),
                    )
                ),
            ),
            bufferPoints = emptyList(),
            bufferDeviceId = deviceA,
            dayStartMs = dayStart,
            dayEndMs = dayEnd,
        )
        assertEquals(listOf(2, 1), traces.single().segments.map { it.size })
    }

    @Test
    fun splitsSegmentsOnDistanceJump() {
        val traces = LocationHistoryAssembler.assemble(
            hours = listOf(
                hour(
                    deviceA,
                    listOf(
                        point(dayStart, lat = 52.0),
                        // ~5.5 km north one minute later — GPS glitch or data hole.
                        point(dayStart + 60_000, lat = 52.05),
                    )
                ),
            ),
            bufferPoints = emptyList(),
            bufferDeviceId = deviceA,
            dayStartMs = dayStart,
            dayEndMs = dayEnd,
        )
        assertEquals(listOf(1, 1), traces.single().segments.map { it.size })
    }

    @Test
    fun statsSumAcrossDevicesAndSkipGapDistance() {
        val traces = LocationHistoryAssembler.assemble(
            hours = listOf(
                // ~1.11 km east-west leg.
                hour(deviceA, listOf(point(dayStart, lon = 13.0), point(dayStart + 60_000, lon = 13.0163))),
                hour(deviceB, listOf(point(dayStart + 1000))),
            ),
            bufferPoints = emptyList(),
            bufferDeviceId = deviceA,
            dayStartMs = dayStart,
            dayEndMs = dayEnd,
        )
        val stats = LocationHistoryAssembler.stats(traces)
        assertEquals(3, stats.pointCount)
        assertEquals(2, stats.deviceCount)
        assertTrue(stats.distanceMeters in 1_000.0..1_250.0, "distance=${stats.distanceMeters}")
        assertEquals(dayStart, stats.firstFixMs)
        assertEquals(dayStart + 60_000, stats.lastFixMs)
    }

    @Test
    fun emptyInputYieldsNoTraces() {
        val traces = LocationHistoryAssembler.assemble(
            hours = emptyList(),
            bufferPoints = emptyList(),
            bufferDeviceId = deviceA,
            dayStartMs = dayStart,
            dayEndMs = dayEnd,
        )
        assertTrue(traces.isEmpty())
        assertEquals(0, LocationHistoryAssembler.stats(traces).pointCount)
    }

    // ── singleDeviceTraces (reusable playback entry point) ──

    @Test
    fun singleDeviceTraces_sorts_segments_and_preserves_steps_and_battery() {
        val min = 60_000L
        val withSensors = BufferedLocationPoint(
            t = dayStart + 40 * min, lat = 52.0, lon = 13.0, acc = 10.0,
            src = "gps", fg = true, steps = 123, bat = 77,
        )
        // Out of order, with a >15 min gap between the 2nd and 3rd points.
        val points = listOf(
            point(dayStart + 16 * min),
            withSensors,
            point(dayStart + 1 * min),
        )
        val traces = LocationHistoryAssembler.singleDeviceTraces(points, deviceA)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(deviceA, trace.deviceId)
        // Sorted: 1m, 16m | 40m  → gap (24 min > 15 min) splits after the 16m point.
        assertEquals(2, trace.segments.size)
        assertEquals(listOf(dayStart + 1 * min, dayStart + 16 * min), trace.segments[0].map { it.t })
        val last = trace.segments[1].single()
        assertEquals(123, last.steps)
        assertEquals(77, last.bat)
    }

    @Test
    fun singleDeviceTraces_empty_is_empty() {
        assertTrue(LocationHistoryAssembler.singleDeviceTraces(emptyList(), deviceA).isEmpty())
    }

    /**
     * The emergency-locate peer viewer's day build (LocationHistoryViewModel.peerDayTraces):
     * retrieved hour-files grouped by device, points filtered to the day, gap-segmented per
     * device via [LocationHistoryAssembler.singleDeviceTraces]. Pinned here as the same
     * composition over the public assembler API.
     */
    @Test
    fun peerDayComposition_groupsByDeviceAndFiltersToDay() {
        val hours = listOf(
            hour(deviceA, listOf(point(dayStart + 2000), point(dayStart - 1))), // one out-of-day
            hour(deviceA, listOf(point(dayStart + 5000))),
            hour(deviceB, listOf(point(dayEnd), point(dayStart + 100))), // dayEnd exclusive
        )
        val traces = hours
            .groupBy { it.deviceId }
            .flatMap { (deviceId, deviceHours) ->
                val dayPoints = deviceHours
                    .flatMap { it.points }
                    .filter { it.t in dayStart until dayEnd }
                LocationHistoryAssembler.singleDeviceTraces(dayPoints, deviceId)
            }

        assertEquals(2, traces.size)
        val a = traces.first { it.deviceId == deviceA }
        assertEquals(
            listOf(dayStart + 2000, dayStart + 5000),
            a.segments.flatten().map { it.t },
        )
        val b = traces.first { it.deviceId == deviceB }
        assertEquals(listOf(dayStart + 100), b.segments.flatten().map { it.t })
    }
}
