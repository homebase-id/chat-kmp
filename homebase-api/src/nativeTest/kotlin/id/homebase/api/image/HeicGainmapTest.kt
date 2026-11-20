@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.homebase.api.image

import id.homebase.api.image.HeicGainmapTestFiles.APPLE_GAINMAP_HEIC
import id.homebase.api.image.HeicGainmapTestFiles.APPLE_HEADROOM_STOPS
import id.homebase.api.image.HeicGainmapTestFiles.ISO_GAINMAP_HEIC
import id.homebase.api.image.HeicGainmapTestFiles.ISO_HEADROOM_STOPS
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An iPhone HEIC's gain map, which only ImageIO reads, goes out through the shared Ultra HDR writer. */
class HeicGainmapTest {
    private val fixtures = listOf("Apple" to (APPLE_GAINMAP_HEIC to APPLE_HEADROOM_STOPS), "ISO" to (ISO_GAINMAP_HEIC to ISO_HEADROOM_STOPS))

    @Test
    fun atHdAndOriginalTheImagePeopleLookAtIsUltraHdrWithTheSourcesHeadroom() = runTest {
        for ((kind, fixture) in fixtures) {
            val (heic, stops) = fixture
            for (setting in listOf(MediaQuality.STANDARD, MediaQuality.HD, MediaQuality.ORIGINAL)) {
                val images = createMediaImages(heic, "k", setting, hdr = true)
                val viewed = images.finalImage?.bytes ?: images.thumbnails.maxBy { it.pixelWidth }.thumbnailBytes
                val label = "$kind $setting"
                val gainmap = UltraHdr.gainmapOf(viewed)
                if (setting == MediaQuality.STANDARD) {
                    assertNull(gainmap, "$label: Standard stays SDR")
                    continue
                }
                assertNotNull(gainmap, "$label: the viewed image keeps the gain map")
                assertTrue(abs(gainmap.metadata.hdrCapacityMax - stops) < 0.05, "$label: headroom ${gainmap.metadata.hdrCapacityMax} stops, the source's $stops")
                assertEquals(ImageSize(48, 64), images.displayedSize, label)
                assertTrue(imageIoSeesTheGainmap(viewed), "$label: ImageIO reads the shared writer's Ultra HDR JPEG's gain map")
                // Turned upright with the photo: its bright half, stored on the left, is now on top.
                val map = Bitmap.makeFromImage(Image.makeFromEncoded(gainmap.jpeg))
                val (top, bottom) = map.grey(map.width / 2, 1) to map.grey(map.width / 2, map.height - 2)
                assertTrue(top > bottom + 100, "$label: gain map top $top, bottom $bottom")
                for (thumb in images.thumbnails.map { it.thumbnailBytes }.filter { it !== viewed }) {
                    assertNull(UltraHdr.gainmapOf(thumb), "$label: smaller thumbnails stay SDR")
                }
            }
        }
    }

    @Test
    fun scrubbedTheGainmapIsStillReadAndWhoTookItIsNot() = runTest {
        for ((kind, fixture) in fixtures) {
            val (heic, stops) = fixture
            assertNotNull(readImageMetadata(heic)?.latitude, "$kind: the fixture has a location")
            val scrubbed = ImageMetadataScrubber.scrub(heic)
            val metadata = readImageMetadata(scrubbed)
            assertNull(metadata?.latitude, "$kind: location")
            assertNull(metadata?.capturedAt, "$kind: capture time")
            assertEquals(6, metadata?.orientation, "$kind: orientation")
            val gainmap = openImageSource(scrubbed).use { assertNotNull(HdrGainmap.auxiliaryOf(it), "$kind: gain map after scrubbing") }
            gainmap.use { assertTrue(abs(it.metadata.hdrCapacityMax - stops) < 0.05, "$kind: headroom ${it.metadata.hdrCapacityMax}") }
        }
    }

    @Test
    fun aPhotoWithoutAGainmapHasNone() {
        val png = ImageUtils.encodeArgbToPng(ArgbImage(IntArray(30 * 20) { 0xFF336699.toInt() }, 30, 20))
        openImageSource(png).use { assertNull(HdrGainmap.auxiliaryOf(it)) }
    }

    @Test
    fun theSharedDecodeKeepsDisplayP3() {
        val source = assertNotNull(ImageIoDecode.open(APPLE_GAINMAP_HEIC))
        try {
            val image = assertNotNull(ImageIoDecode.decode(source, 64))
            try {
                assertTrue(image.colorSpace?.isSRGB == false, "a Display P3 photo decodes as P3, not relabelled sRGB")
            } finally {
                image.close()
            }
        } finally {
            platform.CoreFoundation.CFRelease(source)
        }
    }

    private fun imageIoSeesTheGainmap(jpeg: ByteArray): Boolean {
        val source = assertNotNull(ImageIoDecode.open(jpeg))
        try {
            return platform.ImageIO.CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, 0u, platform.ImageIO.kCGImageAuxiliaryDataTypeISOGainMap)
                ?.also { platform.CoreFoundation.CFRelease(it) } != null
        } finally {
            platform.CoreFoundation.CFRelease(source)
        }
    }

    private fun Bitmap.grey(x: Int, y: Int) = (getColor(x, y) shr 8) and 0xFF
}
