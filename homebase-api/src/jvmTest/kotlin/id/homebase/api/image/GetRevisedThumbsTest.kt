package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetRevisedThumbsTest {

    @Test
    fun largeSource_filtersNearRange_addsSynthetic() {
        // Source max=1700. thresholdMin=1530, thresholdMax=1870.
        // 1600 is in range (1530..1870): 1600 < 1530 is false, 1600 > 1870 is false → FILTERED.
        // 320, 640, 1080 all < 1530 → kept. Synthetic at 1700 added.
        val result = getRevisedThumbs(ImageSize(1700, 1200), baseThumbSizes)
        val dims = mutableListOf<Int>()
        for (t in result) { dims.add(t.maxPixelDimension) }
        assertTrue(dims.contains(320), "Should contain 320")
        assertTrue(dims.contains(640), "Should contain 640")
        assertTrue(dims.contains(1080), "Should contain 1080")
        assertTrue(!dims.contains(1600), "Should NOT contain 1600 (in 90-110% range)")
        assertTrue(dims.contains(1700), "Should contain synthetic 1700")
        assertEquals(4, result.size)
    }

    @Test
    fun veryLargeSource_allKept_noSynthetic() {
        // Source max=5000. All base thumbs < 4500 (90% of 5000), so all kept.
        // keptThumbs.size == thumbs.size → no synthetic added.
        val result = getRevisedThumbs(ImageSize(5000, 5000), baseThumbSizes)
        val dims = mutableListOf<Int>()
        for (t in result) { dims.add(t.maxPixelDimension) }
        assertTrue(dims.contains(320))
        assertTrue(dims.contains(640))
        assertTrue(dims.contains(1080))
        assertTrue(dims.contains(1600))
        assertEquals(4, result.size, "No synthetic needed when all thumbs are kept")
    }

    @Test
    fun smallSource640_filtersLarger_addsSynthetic() {
        // Source max=640. Thumbs > 640 removed (1080, 1600). 320 kept (< 576).
        // 640 is in 90-110% range (576-704), so filtered. Synthetic at 640 added.
        val result = getRevisedThumbs(ImageSize(640, 480), baseThumbSizes)
        val dims = mutableListOf<Int>()
        for (t in result) { dims.add(t.maxPixelDimension) }
        assertTrue(dims.contains(320))
        assertTrue(dims.contains(640), "Should contain synthetic 640")
        assertEquals(2, result.size)
    }

    @Test
    fun tinySource200_onlySynthetic() {
        // Source max=200. All base thumbs (320+) > 200, all filtered. Synthetic at 200.
        val result = getRevisedThumbs(ImageSize(200, 150), baseThumbSizes)
        assertEquals(1, result.size)
        assertEquals(200, result[0].maxPixelDimension)
    }

    @Test
    fun source320_exactMatchThumb_synthetic() {
        // Source max=320. 320 is in 90-110% range (288-352). All others > 320, filtered. Synthetic at 320.
        val result = getRevisedThumbs(ImageSize(320, 240), baseThumbSizes)
        assertEquals(1, result.size)
        assertEquals(320, result[0].maxPixelDimension)
    }

    @Test
    fun syntheticQuality_smallImage_uses84() {
        // Source max=500 (<= 640), synthetic quality should be 84
        val result = getRevisedThumbs(ImageSize(500, 400), baseThumbSizes)
        val synthetic = result.last()
        assertEquals(500, synthetic.maxPixelDimension)
        assertEquals(84, synthetic.quality)
    }

    @Test
    fun syntheticQuality_largeImage_uses76() {
        // Source max=800 (> 640), synthetic quality should be 76
        val result = getRevisedThumbs(ImageSize(800, 600), baseThumbSizes)
        val synthetic = result.last()
        assertEquals(800, synthetic.maxPixelDimension)
        assertEquals(76, synthetic.quality)
    }

    @Test
    fun syntheticMaxBytes_proportional() {
        // Source max=800. Nearest base thumb is 640 (maxBytes=102*1024=104448).
        // Scale = 800/640 = 1.25. Candidate = 104448 * 1.25 = 130560. Clamped to [10240, 1048576].
        val result = getRevisedThumbs(ImageSize(800, 600), baseThumbSizes)
        val synthetic = result.last()
        assertEquals(130560, synthetic.maxBytes)
    }

    @Test
    fun syntheticMaxBytes_clampsLow() {
        // Source max=50. All thumbs filtered. Nearest = 320 (maxBytes=26624).
        // Scale = 50/320 = 0.15625. Candidate = 26624*0.15625 = 4160. Clamped to 10240 (min).
        val result = getRevisedThumbs(ImageSize(50, 50), baseThumbSizes)
        assertEquals(1, result.size)
        assertEquals(10240, result[0].maxBytes)
    }

    @Test
    fun syntheticMaxBytes_clampsHigh() {
        // Need a custom list where filtering occurs and the scale causes maxBytes > 1MB.
        // Two thumbs: 100 is kept (below 90% range), 9500 is filtered (in 90-110% range of 10000).
        // Nearest to 10000 = 9500 (maxBytes=1MB). Scale = 10000/9500 = 1.0526.
        // Candidate = 1048576 * 1.0526 = 1103658. Clamped to 1048576.
        val custom = listOf(
            ThumbnailInstruction(quality = 76, maxPixelDimension = 100, maxBytes = 1024 * 1024),
            ThumbnailInstruction(quality = 76, maxPixelDimension = 9500, maxBytes = 1024 * 1024)
        )
        val result = getRevisedThumbs(ImageSize(10000, 10000), custom)
        val synthetic = result.last()
        assertEquals(10000, synthetic.maxPixelDimension)
        assertEquals(1048576, synthetic.maxBytes)
    }

    @Test
    fun resultIsSorted() {
        val result = getRevisedThumbs(ImageSize(2000, 1500), baseThumbSizes)
        for (i in 0 until result.size - 1) {
            assertTrue(
                result[i].maxPixelDimension <= result[i + 1].maxPixelDimension,
                "Result not sorted: ${result[i].maxPixelDimension} > ${result[i + 1].maxPixelDimension}"
            )
        }
    }

    @Test
    fun customThumbSizes_respected() {
        val custom = listOf(
            ThumbnailInstruction(quality = 80, maxPixelDimension = 100, maxBytes = 10 * 1024),
            ThumbnailInstruction(quality = 80, maxPixelDimension = 200, maxBytes = 50 * 1024),
            ThumbnailInstruction(quality = 80, maxPixelDimension = 500, maxBytes = 200 * 1024)
        )
        val result = getRevisedThumbs(ImageSize(300, 200), custom)
        // 100 < 270 (90% of 300), kept. 200 < 270, kept. 500 > 300, filtered. Synthetic at 300.
        val dims = mutableListOf<Int>()
        for (t in result) { dims.add(t.maxPixelDimension) }
        assertTrue(dims.contains(100))
        assertTrue(dims.contains(200))
        assertTrue(!dims.contains(500))
        assertTrue(dims.contains(300))
    }

    @Test
    fun source1080_nearThreshold() {
        // Source max=1080. The 1080 base thumb is in 90-110% range (972-1188), so filtered.
        // 320, 640 kept (< 972). Synthetic at 1080 added.
        val result = getRevisedThumbs(ImageSize(1080, 720), baseThumbSizes)
        val dims = mutableListOf<Int>()
        for (t in result) { dims.add(t.maxPixelDimension) }
        assertTrue(dims.contains(320))
        assertTrue(dims.contains(640))
        assertTrue(dims.contains(1080))
        assertEquals(3, result.size)
    }

    @Test
    fun sourceExactlyAtThumb1600() {
        // Source max=1600. 1600 in 90-110% range (1440-1760). 320, 640, 1080 all < 1440, kept.
        // Synthetic at 1600.
        val result = getRevisedThumbs(ImageSize(1600, 1200), baseThumbSizes)
        val dims = mutableListOf<Int>()
        for (t in result) { dims.add(t.maxPixelDimension) }
        assertTrue(dims.contains(320))
        assertTrue(dims.contains(640))
        assertTrue(dims.contains(1080))
        assertTrue(dims.contains(1600))
        assertEquals(4, result.size)
    }

    @Test
    fun emptyThumbsList_returnsEmpty() {
        val result = getRevisedThumbs(ImageSize(800, 600), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun singleThumbList_noSyntheticNeeded() {
        val single = listOf(ThumbnailInstruction(quality = 84, maxPixelDimension = 320, maxBytes = 26 * 1024))
        // Source max=500. 320 < 500 and 320 < 450 (90% of 500), kept.
        // keptThumbs.size (1) == thumbs.size (1) => no synthetic added.
        val result = getRevisedThumbs(ImageSize(500, 400), single)
        assertEquals(1, result.size)
        assertEquals(320, result[0].maxPixelDimension)
    }
}
