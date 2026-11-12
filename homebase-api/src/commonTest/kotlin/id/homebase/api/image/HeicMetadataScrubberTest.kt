package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HeicMetadataScrubberTest {
    private val source = HeicFixtures.geotaggedRotated
    private val scrubbed = ImageMetadataScrubber.scrub(source)

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { at -> needle.indices.all { this[at + it] == needle[it] } }

    private fun ByteArray.contains(text: String) = contains(text.encodeToByteArray())

    @Test
    fun theFixtureCarriesTheMetadataUnderTest() {
        listOf("SECRETSERIAL", "SecretCamera", "photoshop:DateCreated").forEach { assertTrue(source.contains(it), it) }
    }

    @Test
    fun exifAndXmpAreGone() {
        listOf("SECRETSERIAL", "SecretCamera", "photoshop:DateCreated", "2026:01:02", "x:xmpmeta").forEach {
            assertFalse(scrubbed.contains(it), "$it survived")
        }
    }

    @Test
    fun theContainerAndImageDataAreByteIdentical() {
        assertEquals(source.size, scrubbed.size)
        assertContentEquals(source.copyOfRange(0, 572), scrubbed.copyOfRange(0, 572), "ftyp and meta, irot included")
        assertContentEquals(source.copyOfRange(3227, source.size), scrubbed.copyOfRange(3227, source.size), "hvc1 data")
    }

    @Test
    fun theExifItemKeepsOnlyTheOrientation() {
        val exif = scrubbed.copyOfRange(572, 824)
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6), exif.copyOfRange(0, 23))
        assertTrue(exif.copyOfRange(30, exif.size).all { it == 0.toByte() })
    }

    @Test
    fun aScrubbedFileIsLeftAlone() {
        assertSame(scrubbed, ImageMetadataScrubber.scrub(scrubbed))
    }

    @Test
    fun aHeicWithNoMetadataItemsIsLeftAlone() {
        val noMetadata = source.copyOf()
        for (at in 0 until 572 - 4) {
            val type = noMetadata.decodeToString(at, at + 4)
            if (type == "Exif" || type == "mime") "zzzz".encodeToByteArray().copyInto(noMetadata, at)
        }
        assertSame(noMetadata, ImageMetadataScrubber.scrub(noMetadata))
    }

    @Test
    fun aHeicCutOffBeforeItsMetadataEnds_isUnparseable() {
        for (cut in listOf(300, 700)) {
            assertEquals(ImageMetadataScrubber.Outcome.Unparseable, ImageMetadataScrubber.scrubWithOutcome(source.copyOfRange(0, cut)), "cut at $cut")
        }
    }

    @Test
    fun aTruncatedHeicDoesNotThrow() {
        for (cut in listOf(12, 40, 140, 300, 520, 560, 700, 3000)) {
            ImageMetadataScrubber.scrub(source.copyOfRange(0, cut))
        }
    }
}
