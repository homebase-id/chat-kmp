package id.homebase.api.image

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaImagePlanTest {

    private fun sizes(images: List<PlannedImage>) = images.map { "${it.width}x${it.height}" }

    private fun assertPlan(
        width: Int,
        height: Int,
        setting: MediaQuality,
        thumbnails: List<String>,
        final: String?,
    ) {
        val plan = planMediaImages(ImageSize(width, height), setting)
        assertEquals(thumbnails, sizes(plan.thumbnails), "$width x $height $setting thumbnails")
        assertEquals(final, plan.finalImage?.let { "${it.width}x${it.height}" }, "$width x $height $setting final")
    }

    @Test
    fun workedExample_4000x3000_withQualities() {
        val standard = planMediaImages(ImageSize(4000, 3000), MediaQuality.STANDARD)
        assertEquals(listOf("320x240", "640x480", "1080x810"), sizes(standard.thumbnails))
        assertEquals(listOf(70, 70, 70), standard.thumbnails.map { it.quality })
        assertEquals(PlannedImage(2048, 1536, 60), standard.finalImage)

        val hd = planMediaImages(ImageSize(4000, 3000), MediaQuality.HD)
        assertEquals(listOf("320x240", "640x480", "1080x810", "2048x1536"), sizes(hd.thumbnails))
        assertEquals(listOf(80, 80, 76, 70), hd.thumbnails.map { it.quality })
        assertEquals(PlannedImage(4000, 3000, 80), hd.finalImage)

        val original = planMediaImages(ImageSize(4000, 3000), MediaQuality.ORIGINAL)
        assertEquals(listOf("320x240", "640x480", "1080x810", "2048x1536"), sizes(original.thumbnails))
        assertEquals(listOf(80, 80, 80, 76), original.thumbnails.map { it.quality })
        assertNull(original.finalImage)
    }

    @Test
    fun workedExample_4000x2250() {
        // 1440x810 is only 1.42x below 2048, so the planner skips it wherever it makes the final image.
        val thumbs = listOf("427x240", "854x480")
        assertPlan(4000, 2250, MediaQuality.STANDARD, thumbs, "2048x1152")
        assertPlan(4000, 2250, MediaQuality.HD, thumbs + "2048x1152", "4000x2250")
        assertPlan(4000, 2250, MediaQuality.ORIGINAL, thumbs + listOf("1440x810", "2048x1152"), null)
    }

    @Test
    fun workedExample_1179x2556() {
        val thumbs = listOf("240x521", "480x1041")
        assertPlan(1179, 2556, MediaQuality.STANDARD, thumbs, "1179x2556")
        assertPlan(1179, 2556, MediaQuality.HD, thumbs, "1179x2556")
        assertPlan(1179, 2556, MediaQuality.ORIGINAL, thumbs + listOf("810x1757", "1179x2556"), null)
    }

    @Test
    fun workedExample_1179x8000() {
        val thumbs = listOf("94x640", "189x1280", "318x2160")
        assertPlan(1179, 8000, MediaQuality.STANDARD, thumbs, "1179x8000")
        assertPlan(1179, 8000, MediaQuality.HD, thumbs, "1179x8000")
        assertPlan(1179, 8000, MediaQuality.ORIGINAL, thumbs + "1179x8000", null)
    }

    @Test
    fun workedExample_12000x4000() {
        val thumbs = listOf("640x213", "1280x427", "2160x720")
        assertPlan(12000, 4000, MediaQuality.STANDARD, thumbs, "3240x1080")
        assertPlan(12000, 4000, MediaQuality.HD, thumbs + "3240x1080", "6480x2160")
        assertPlan(12000, 4000, MediaQuality.ORIGINAL, thumbs + "3240x1080", null)
    }

    @Test
    fun workedExample_1100x825_withQualities() {
        // The 1100x825 thumbnail would duplicate the final image, so only Original keeps it.
        val standard = planMediaImages(ImageSize(1100, 825), MediaQuality.STANDARD)
        assertEquals(listOf("320x240", "640x480"), sizes(standard.thumbnails))
        assertEquals(PlannedImage(1100, 825, 70), standard.finalImage, "Takes the near thumbnail's quality")

        val hd = planMediaImages(ImageSize(1100, 825), MediaQuality.HD)
        assertEquals(listOf("320x240", "640x480"), sizes(hd.thumbnails))
        assertEquals(PlannedImage(1100, 825, 80), hd.finalImage)

        val original = planMediaImages(ImageSize(1100, 825), MediaQuality.ORIGINAL)
        assertEquals(listOf("320x240", "640x480", "1100x825"), sizes(original.thumbnails))
        assertEquals(80, original.thumbnails.last().quality)
    }

    @Test
    fun workedExample_1000x750() {
        val thumbs = listOf("320x240", "640x480")
        val standard = planMediaImages(ImageSize(1000, 750), MediaQuality.STANDARD)
        assertEquals(thumbs, sizes(standard.thumbnails))
        assertEquals(PlannedImage(1000, 750, 70), standard.finalImage)
        val hd = planMediaImages(ImageSize(1000, 750), MediaQuality.HD)
        assertEquals(thumbs, sizes(hd.thumbnails))
        assertEquals(PlannedImage(1000, 750, 80), hd.finalImage)
        assertPlan(1000, 750, MediaQuality.ORIGINAL, thumbs + "1000x750", null)
    }

    @Test
    fun bandEdges() {
        val small = listOf("320x240", "640x480")
        assertPlan(1200, 900, MediaQuality.STANDARD, small, "1200x900")
        assertEquals(70, planMediaImages(ImageSize(1200, 900), MediaQuality.STANDARD).finalImage?.quality)
        assertPlan(1201, 901, MediaQuality.STANDARD, small, "1201x901")
        assertEquals(60, planMediaImages(ImageSize(1201, 901), MediaQuality.STANDARD).finalImage?.quality)
        assertPlan(1201, 901, MediaQuality.HD, small, "1201x901")
        assertPlan(2275, 1706, MediaQuality.STANDARD, listOf("321x241", "641x481", "1081x811"), "2275x1706")
        assertPlan(2276, 1707, MediaQuality.STANDARD, small + "1080x810", "2048x1536")
        assertPlan(2276, 1707, MediaQuality.HD, small + "1080x810", "2276x1707")
        assertEquals("4551x3413", planMediaImages(ImageSize(4551, 3413), MediaQuality.HD).finalImage?.let { "${it.width}x${it.height}" })
        assertEquals("4096x3072", planMediaImages(ImageSize(4552, 3414), MediaQuality.HD).finalImage?.let { "${it.width}x${it.height}" })
    }

    @Test
    fun aThumbnailEnlargedByTheBandRemembersItsRowSize() {
        val plan = planMediaImages(ImageSize(1100, 825), MediaQuality.ORIGINAL)
        assertEquals(ImageSize(1080, 810), plan.thumbnails.last().rowSize)
        assertNull(plan.thumbnails.first().rowSize)
    }

    @Test
    fun workedExample_600x450() {
        assertPlan(600, 450, MediaQuality.STANDARD, listOf("320x240"), "600x450")
        assertPlan(600, 450, MediaQuality.HD, listOf("320x240"), "600x450")
        assertPlan(600, 450, MediaQuality.ORIGINAL, listOf("320x240", "600x450"), null)
    }

    @Test
    fun workedExample_200x150() {
        assertPlan(200, 150, MediaQuality.STANDARD, listOf("200x150"), "200x150")
        assertPlan(200, 150, MediaQuality.HD, listOf("200x150"), "200x150")
        assertPlan(200, 150, MediaQuality.ORIGINAL, listOf("200x150"), null)
    }

    @Test
    fun tinyHasNoMinimumShortEdgeAndNeverLeavesOnePixel() {
        assertEquals(PlannedImage(20, 15, 76), planMediaImages(ImageSize(4000, 3000), MediaQuality.HD).tiny)
        assertEquals(PlannedImage(20, 1, 76), planMediaImages(ImageSize(4000, 2), MediaQuality.HD).tiny)
    }

    @Test
    fun portraitKeepsItsOrientation() {
        val plan = planMediaImages(ImageSize(3000, 4000), MediaQuality.STANDARD)
        assertEquals("240x320", sizes(plan.thumbnails).first())
        assertEquals(PlannedImage(1536, 2048, 60), plan.finalImage)
    }

    @Test
    fun randomSizesStayInsideTheSourceAndOrdered() {
        val random = Random(2230)
        // Far more than this runs past the web test runner's time limit on a busy CI machine.
        repeat(5_000) {
            val width = random.nextInt(1, 12001)
            val height = random.nextInt(1, 12001)
            for (setting in MediaQuality.entries) {
                val plan = planMediaImages(ImageSize(width, height), setting)
                val all = plan.thumbnails + listOfNotNull(plan.finalImage)
                assertTrue(plan.thumbnails.size + 1 <= 5, "Server allows 5 thumbnails, tiny aside it's fewer")
                var previous = 0
                for (image in all) {
                    assertTrue(image.width in 1..width && image.height in 1..height, "$width x $height -> $image")
                    assertTrue(image.longEdge >= previous, "$width x $height $setting got smaller going down")
                    previous = image.longEdge
                }
                val sourceLong = max(width, height)
                for (image in all) {
                    if (image.longEdge.toLong() * 10 >= sourceLong.toLong() * 9) {
                        assertEquals(width to height, image.width to image.height, "$width x $height $setting within 10%")
                    }
                }
                plan.thumbnails.zipWithNext { a, b ->
                    assertTrue(a.longEdge.toLong() * 10 < b.longEdge.toLong() * 9, "$width x $height $setting near-duplicate thumbnails")
                }
                val final = plan.finalImage
                if (final != null) {
                    (plan.thumbnails + final).zipWithNext().drop(1).forEach { (thumbnail, above) ->
                        assertTrue(above.longEdge.toLong() * 2 >= thumbnail.longEdge.toLong() * 3, "$width x $height $setting kept $thumbnail under $above")
                    }
                }
                val largest = plan.thumbnails.lastOrNull()
                if (largest != null && final != null && largest.longEdge.toLong() * 10 >= final.longEdge.toLong() * 9) {
                    assertTrue(final.quality >= largest.quality, "$width x $height $setting final looks worse than its thumbnail")
                }
                assertTrue(max(plan.tiny.width, plan.tiny.height) <= 20)
                assertTrue(min(plan.tiny.width, plan.tiny.height) >= 1)
            }
        }
    }
}
