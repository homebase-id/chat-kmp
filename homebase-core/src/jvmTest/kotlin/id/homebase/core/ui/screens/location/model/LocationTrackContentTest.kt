package id.homebase.core.ui.screens.location.model

import id.homebase.api.sync.database.BufferedLocationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LocationTrackContentTest {

    private val deviceId = Uuid.parse("5e0a1111-2222-3333-4444-555566667777")
    private val hourStart = 1_765_360_800_000L

    private fun point(
        offsetMs: Long,
        lat: Double = 52.31235,
        lon: Double = 13.42346,
        acc: Double? = 12.0,
        spd: Double? = 1.4,
        hdg: Double? = 271.0,
        src: String = "gps",
        fg: Boolean = true,
    ) = BufferedLocationPoint(
        t = hourStart + offsetMs, lat = lat, lon = lon, acc = acc,
        spd = spd, hdg = hdg, src = src, fg = fg,
    )

    @Test
    fun hourFileUidIsDeterministicAndDeviceScoped() {
        val a = locationHourFileUid(deviceId, hourStart)
        val b = locationHourFileUid(deviceId, hourStart)
        val otherHour = locationHourFileUid(deviceId, hourStart + HOUR_MS)
        val otherDevice = locationHourFileUid(Uuid.random(), hourStart)
        assertEquals(a, b)
        assertTrue(a != otherHour)
        assertTrue(a != otherDevice)
    }

    @Test
    fun deviceFileUidIsDeterministicAndDeviceScoped() {
        assertEquals(locationDeviceFileUid(deviceId), locationDeviceFileUid(deviceId))
        assertTrue(locationDeviceFileUid(deviceId) != locationDeviceFileUid(Uuid.random()))
        // Device profile uid never collides with the device's hour files.
        assertTrue(locationDeviceFileUid(deviceId) != locationHourFileUid(deviceId, hourStart))
    }

    @Test
    fun deviceProfileRoundTrip() {
        val profile = LocationDeviceProfile(
            deviceId = deviceId.toString(),
            name = "Pixel 9 Pro",
            platform = "android",
        )
        val decoded = assertNotNull(
            LocationDeviceProfile.decode(
                id.homebase.api.serialization.OdinSystemSerializer.serialize(profile)
            )
        )
        assertEquals(profile, decoded)
        assertEquals(null, LocationDeviceProfile.decode("not json"))
    }

    @Test
    fun hourBucketing() {
        assertEquals(hourStart, hourStartMs(hourStart))
        assertEquals(hourStart, hourStartMs(hourStart + HOUR_MS - 1))
        assertEquals(hourStart + HOUR_MS, hourStartMs(hourStart + HOUR_MS))
    }

    @Test
    fun headerRoundTripPreservesPointsWithinQuantization() {
        val points = listOf(
            point(0),
            point(22_000, lat = 52.31301, lon = 13.42410, acc = 8.0, spd = 1.3, hdg = 268.0),
            point(841_000, lat = 52.31999, lon = 13.43102, acc = 25.0, spd = null, hdg = null, src = "slc", fg = false),
        )
        val (json, stored) = LocationTrackCodec.encodeHeader(deviceId, hourStart, points)
        assertEquals(points.size, stored.size)

        val decoded = assertNotNull(LocationTrackCodec.decodeHeader(json))
        assertEquals(deviceId, decoded.deviceId)
        assertEquals(hourStart, decoded.hourStartMs)
        assertEquals(points.size, decoded.fullCount)
        assertTrue(!decoded.hasOverflowPayload)
        assertEquals(points.size, decoded.points.size)

        for ((orig, round) in points.zip(decoded.points)) {
            assertEquals(orig.t / 1000, round.t / 1000)        // second resolution
            assertEquals(orig.lat, round.lat, 1e-5)            // 1e-5 deg quantization
            assertEquals(orig.lon, round.lon, 1e-5)
            assertEquals(orig.src, round.src)
            assertEquals(orig.fg, round.fg)
            if (orig.spd == null) assertEquals(null, round.spd)
            else assertEquals(orig.spd!!, round.spd!!, 0.05)   // 0.1 m/s quantization
        }
    }

    @Test
    fun headerThinsToFitBudgetAndKeepsEndpoints() {
        // 1200 points ≈ a 3s-cadence hour — far beyond the header budget.
        val points = (0 until 1200).map { i ->
            point(i * 3_000L, lat = 52.0 + i * 1e-4, lon = 13.0 + i * 1e-4)
        }
        val (json, stored) = LocationTrackCodec.encodeHeader(deviceId, hourStart, points)

        assertTrue(json.encodeToByteArray().size <= LOCATION_HEADER_PLAINTEXT_BUDGET)
        assertTrue(stored.size < points.size)
        assertTrue(stored.size > 50, "thinning should keep a useful trace, got ${stored.size}")
        assertEquals(points.first().t, stored.first().t)
        assertEquals(points.last().t, stored.last().t)

        val decoded = assertNotNull(LocationTrackCodec.decodeHeader(json))
        assertTrue(decoded.hasOverflowPayload)
        assertEquals(points.size, decoded.fullCount)
        assertEquals(stored.size, decoded.points.size)
    }

    @Test
    fun payloadRoundTripIsLossless() {
        val points = listOf(
            BufferedLocationPoint(
                t = hourStart + 1234, lat = 52.312345678, lon = 13.423456789,
                acc = 12.5, alt = 38.2, altAcc = 4.0, spd = 1.44, hdg = 271.3,
                src = "fused", fg = true,
            ),
            BufferedLocationPoint(
                t = hourStart + 60_000, lat = -33.9, lon = 151.2,
                acc = null, src = "net", fg = false,
            ),
        )
        val json = LocationTrackCodec.encodePayload(deviceId, hourStart, points)
        val decoded = assertNotNull(LocationTrackCodec.decodePayload(json))
        assertEquals(points, decoded.points)
        assertEquals(deviceId, decoded.deviceId)
        assertEquals(hourStart, decoded.hourStartMs)
    }

    @Test
    fun thinUniformKeepsOrderAndEndpoints() {
        val points = (0 until 100).map { point(it * 1000L) }
        val thinned = LocationTrackCodec.thinUniform(points, 10)
        assertEquals(10, thinned.size)
        assertEquals(points.first(), thinned.first())
        assertEquals(points.last(), thinned.last())
        assertEquals(thinned, thinned.sortedBy { it.t })
    }
}
