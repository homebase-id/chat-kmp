package id.homebase.api.image

import id.homebase.api.image.HeicGainmapTestFiles.APPLE_GAINMAP_HEIC
import id.homebase.api.image.HeicGainmapTestFiles.ISO_GAINMAP_HEIC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HeicGainmapScrubTest {
    private fun ByteArray.has(text: String) = decodeToString().contains(text)

    @Test
    fun anIphoneGainmapKeepsItsXmpAndHeadroomAndLosesWhoTookIt() {
        assertTrue(APPLE_GAINMAP_HEIC.has("SERIAL123") && APPLE_GAINMAP_HEIC.has("2026:01:02"), "the fixture identifies its taker")
        val scrubbed = assertIs<ImageMetadataScrubber.Outcome.Scrubbed>(ImageMetadataScrubber.scrubWithOutcome(APPLE_GAINMAP_HEIC)).bytes
        assertEquals(APPLE_GAINMAP_HEIC.size, scrubbed.size)
        assertFalse(scrubbed.has("SERIAL123"), "body serial")
        assertFalse(scrubbed.has("2026:01:02"), "capture time")
        assertTrue(scrubbed.has("<HDRGainMap:HDRGainMapVersion>65536</HDRGainMap:HDRGainMapVersion>"), "the gain map's XMP")
        val exif = ImageMetadataScrubber.readBlocks(scrubbed)?.exif
        assertTrue(exif != null && exif.decodeToString().contains("Apple iOS"), "the MakerNote with the headroom")
        assertEquals(null, ImageMetadataScrubber.readBlocks(scrubbed)?.xmp?.decodeToString()?.takeIf { "GPS" in it || "2026" in it })
    }

    @Test
    fun anIsoGainmapIsInItsOwnItemAndSurvives() {
        val scrubbed = assertIs<ImageMetadataScrubber.Outcome.Scrubbed>(ImageMetadataScrubber.scrubWithOutcome(ISO_GAINMAP_HEIC)).bytes
        assertFalse(scrubbed.has("SERIAL123"), "body serial")
        assertFalse(scrubbed.has("2026:01:02"), "capture time")
        val idat = "idat".encodeToByteArray()
        val tmap = (0..ISO_GAINMAP_HEIC.size - 4).first { at -> idat.indices.all { ISO_GAINMAP_HEIC[at + it] == idat[it] } }
        assertTrue(ISO_GAINMAP_HEIC.copyOfRange(tmap, tmap + 70).contentEquals(scrubbed.copyOfRange(tmap, tmap + 70)), "the tone map is untouched")
    }
}
