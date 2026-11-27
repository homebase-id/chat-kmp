package id.homebase.photobase.info

import id.homebase.api.client.location.WebMercator
import id.homebase.api.media.MediaLocation
import id.homebase.common.util.formatBytes
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PhotoInfoFormatTest {
    @Test
    fun megapixelsAreToOneDecimal() {
        assertEquals("12.2", megapixels(4032, 3024))
        assertEquals("48", megapixels(8000, 6000))
    }

    @Test
    fun aShortExposureIsAFractionAndALongOneSeconds() {
        assertEquals("1/250", shutter(1.0 / 250))
        assertEquals("1/3", shutter(0.3))
        assertEquals("2.5", shutter(2.5))
        assertEquals("1", shutter(1.0))
    }

    @Test
    fun apertureAndFocalLengthDropAZeroDecimal() {
        assertEquals("1.8", aperture(1.8))
        assertEquals("2", aperture(2.0))
        assertEquals("4.2", focalLength(4.24))
        assertEquals("26", focalLength(26.0))
    }

    @Test
    fun theFileSizeIsShownInItsUnit() {
        assertEquals("3.1 MB", formatBytes(3_300_000))
    }

    @Test
    fun theZoneIsItsOffsetWhenTheMomentWasTaken() {
        val winter = Instant.parse("2026-01-15T12:00:00Z")
        val summer = Instant.parse("2026-07-15T12:00:00Z")

        assertEquals("GMT", zoneLabel(winter, TimeZone.UTC))
        assertEquals("GMT-8", zoneLabel(winter, TimeZone.of("America/Los_Angeles")))
        assertEquals("GMT-7", zoneLabel(summer, TimeZone.of("America/Los_Angeles")))
        assertEquals("GMT+5:30", zoneLabel(winter, TimeZone.of("Asia/Kolkata")))
    }

    @Test
    fun theMakeIsNotSaidTwice() {
        assertEquals("Canon EOS R5", cameraName("Canon", "Canon EOS R5"))
        assertEquals("Apple iPhone 15", cameraName("Apple", "iPhone 15"))
        assertEquals("Apple", cameraName("Apple", null))
        assertNull(cameraName(null, null))
    }

    @Test
    fun coordinatesAreToFiveDecimals() {
        assertEquals("37.77490, -122.41940", coordinates(MediaLocation(37.7749, -122.4194)))
        assertEquals("0.00000, 0.00001", coordinates(MediaLocation(-0.000001, 0.00001)))
    }

    @Test
    fun thePinIsOnThePlaceAndTheMapCentredOnIt() {
        val at = MediaLocation(37.7749, -122.4194)
        val focus = MapFocus(at)
        val (x, y) = WebMercator.latLonToUnit(at.latitude, at.longitude)

        assertEquals(x, focus.x)
        assertEquals(y, focus.y)
        assertEquals(x, (focus.bbox[0] + focus.bbox[2]) / 2, 1e-12)
        assertEquals(y, (focus.bbox[1] + focus.bbox[3]) / 2, 1e-12)
        // One tile across at the map's zoom.
        assertEquals(1.0 / (1 shl MAP_ZOOM), focus.bbox[2] - focus.bbox[0], 1e-12)
        val (back, _) = WebMercator.unitToLatLon(focus.x, focus.y)
        assertTrue(kotlin.math.abs(back - at.latitude) < 1e-9)
    }
}
