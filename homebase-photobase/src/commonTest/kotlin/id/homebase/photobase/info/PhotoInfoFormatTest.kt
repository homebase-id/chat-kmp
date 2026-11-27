package id.homebase.photobase.info

import id.homebase.api.client.location.WebMercator
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.image.ImageMetadata
import id.homebase.api.media.MediaLocation
import id.homebase.chat.event.offsetLabel
import id.homebase.common.util.formatDecimal
import id.homebase.common.util.formatBytes
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class PhotoInfoFormatTest {
    @Test
    fun megapixelsAreToOneDecimal() {
        assertEquals("12.2", megapixels(4032, 3024))
        assertEquals("48", megapixels(8000, 6000))
        assertNull(megapixels(200, 200), "a sticker isn't 0 MP")
    }

    @Test
    fun aShortExposureIsAFractionAndALongOneSeconds() {
        assertEquals("1/250", shutter(1.0 / 250))
        assertEquals("1/3", shutter(1.0 / 3))
        assertEquals("1/2", shutter(0.5))
        assertEquals("0.8", shutter(0.8))
        assertEquals("0.3", shutter(0.3))
        assertEquals("0.4", shutter(0.4))
        assertEquals("1/81", shutter(0.0123))
        assertEquals("1/3817", shutter(0.000262))
        assertEquals("2.5", shutter(2.5))
        assertEquals("1", shutter(1.0))
        assertEquals("1", shutter(0.97), "not 1/1")
    }

    @Test
    fun apertureAndFocalLengthDropAZeroDecimal() {
        assertEquals("1.8", formatDecimal(1.8, 1))
        assertEquals("2", formatDecimal(2.0, 1))
        assertEquals("4.2", formatDecimal(4.24, 1))
        assertEquals("26", formatDecimal(26.0, 1))
    }

    @Test
    fun theFileSizeIsShownInItsUnit() {
        assertEquals("3.1 MB", formatBytes(3_300_000))
    }

    @Test
    fun theZoneIsItsOffsetWhenTheMomentWasTaken() {
        val winter = Instant.parse("2026-01-15T12:00:00Z")
        val summer = Instant.parse("2026-07-15T12:00:00Z")

        assertEquals("GMT+0", offsetLabel(winter, TimeZone.UTC))
        assertEquals("GMT-8", offsetLabel(winter, TimeZone.of("America/Los_Angeles")))
        assertEquals("GMT-7", offsetLabel(summer, TimeZone.of("America/Los_Angeles")))
        assertEquals("GMT+5:30", offsetLabel(winter, TimeZone.of("Asia/Kolkata")))
    }

    @Test
    fun aPhotoTakenInTokyoShowsTokyosClockWhereverItsViewed() {
        val at = Instant.parse("2026-01-15T06:00:00Z")
        val newYork = TimeZone.of("America/New_York")

        val shown = shiftedTo(at, UtcOffset(hours = 9), newYork)

        assertEquals(LocalDateTime(2026, 1, 15, 15, 0), shown.toLocalDateTime(newYork))
        assertEquals("GMT+9", offsetLabel(at, UtcOffset(hours = 9).asTimeZone()))
    }

    @Test
    fun theExifsOwnTimeAndOffsetWinOverTheBackupsReading() {
        // Backed up where no offset was read: 15:00 taken as New York's, 20:00Z.
        val backedUp = Instant.parse("2026-01-15T20:00:00Z").toEpochMilliseconds()
        val exif = ImageMetadata(capturedAt = LocalDateTime(2026, 1, 15, 15, 0), captureUtcOffset = UtcOffset(hours = 9))

        assertEquals(Instant.parse("2026-01-15T06:00:00Z") to UtcOffset(hours = 9), takenAt(backedUp, exif))
        assertEquals(Instant.fromEpochMilliseconds(backedUp) to UtcOffset(hours = 9), takenAt(backedUp, exif.copy(capturedAt = null)))
        assertEquals(Instant.fromEpochMilliseconds(backedUp) to null, takenAt(backedUp, null))
    }

    @Test
    fun theShiftHoldsAcrossTheViewersChangeOfClock() {
        // Berlin goes to summer time at 01:00 UTC on 29 March; 23:30 UTC the night before is 08:30 in Tokyo.
        val berlin = TimeZone.of("Europe/Berlin")
        val shown = shiftedTo(Instant.parse("2026-03-28T23:30:00Z"), UtcOffset(hours = 9), berlin)

        assertEquals(LocalDateTime(2026, 3, 29, 8, 30), shown.toLocalDateTime(berlin))
    }

    @Test
    fun theMakeIsNotSaidTwice() {
        assertEquals("Canon EOS R5", cameraName("Canon", "Canon EOS R5"))
        assertEquals("Apple iPhone 15", cameraName("Apple", "iPhone 15"))
        assertEquals("NIKON D850", cameraName("NIKON CORPORATION", "NIKON D850"))
        assertEquals("Apple", cameraName("Apple", null))
        assertNull(cameraName(null, null))
    }

    @Test
    fun theMapLinkSearchesForThePoint() {
        assertEquals("https://www.openstreetmap.org/?mlat=37.77490&mlon=-122.41940#map=15/37.77490/-122.41940", mapsUrl(MediaLocation(37.7749, -122.4194)))
        assertEquals("https://www.openstreetmap.org/?mlat=51.47780&mlon=-0.00050#map=15/51.47780/-0.00050", mapsUrl(MediaLocation(51.4778, -0.0005)), "never 5.0E-4")
    }

    @Test
    fun coordinatesAreToFiveDecimals() {
        assertEquals("37.77490, -122.41940", coordinates(MediaLocation(37.7749, -122.4194)))
        assertEquals("0.00000, 0.00001", coordinates(MediaLocation(-0.000001, 0.00001)))
    }

    @Test
    fun theMapIsCentredOnThePinAndOneTileAcross() {
        val (x, y) = WebMercator.latLonToUnit(37.7749, -122.4194)
        val box = mapBox(x, y)

        assertEquals(x, (box[0] + box[2]) / 2, 1e-12)
        assertEquals(y, (box[1] + box[3]) / 2, 1e-12)
        assertEquals(1.0 / (1 shl MAP_ZOOM), box[2] - box[0], 1e-12)
        assertEquals(box[2] - box[0], box[3] - box[1], 1e-12)
    }

    @Test
    fun theFilesNameSizeAndResolutionComeFromItsDescriptor() {
        val image = PayloadDescriptor(
            key = "img",
            contentType = "image/heic",
            bytesWritten = 2_400_000,
            descriptorContent = DescriptorContentV1(kind = DescriptorContentV1.Kind.IMAGE, name = "IMG_0001.HEIC", pixelWidth = 4032, pixelHeight = 3024).encode(),
        )

        assertEquals(FileFacts("IMG_0001.HEIC", 4032, 3024, 2_400_000), fileFacts(image))
        assertEquals(FileFacts(null, null, null, null), fileFacts(PayloadDescriptor(key = "img", contentType = "image/jpeg")))
    }
}
