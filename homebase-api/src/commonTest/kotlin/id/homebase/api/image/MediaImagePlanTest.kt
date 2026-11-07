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
        val thumbs = listOf("427x240", "854x480", "1440x810")
        assertPlan(4000, 2250, MediaQuality.STANDARD, thumbs, "2048x1152")
        assertPlan(4000, 2250, MediaQuality.HD, thumbs + "2048x1152", "4000x2250")
        assertPlan(4000, 2250, MediaQuality.ORIGINAL, thumbs + "2048x1152", null)
    }

    @Test
    fun workedExample_1179x2556() {
        val thumbs = listOf("240x521", "480x1041", "810x1757")
        assertPlan(1179, 2556, MediaQuality.STANDARD, thumbs, "1080x2342")
        assertPlan(1179, 2556, MediaQuality.HD, thumbs + "1080x2342", "1179x2556")
        assertPlan(1179, 2556, MediaQuality.ORIGINAL, thumbs + "1080x2342", null)
    }

    @Test
    fun workedExample_1179x8000() {
        val thumbs = listOf("94x640", "189x1280", "318x2160")
        assertPlan(1179, 8000, MediaQuality.STANDARD, thumbs, "1080x7329")
        assertPlan(1179, 8000, MediaQuality.HD, thumbs + "1080x7329", "1179x8000")
        assertPlan(1179, 8000, MediaQuality.ORIGINAL, thumbs + "1080x7329", null)
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
        val standard = planMediaImages(ImageSize(1100, 825), MediaQuality.STANDARD)
        assertEquals(listOf("320x240", "640x480", "1080x810"), sizes(standard.thumbnails))
        assertEquals(PlannedImage(1100, 825, 70), standard.finalImage, "Takes the near thumbnail's quality")

        val hd = planMediaImages(ImageSize(1100, 825), MediaQuality.HD)
        assertEquals(listOf("320x240", "640x480", "1100x825"), sizes(hd.thumbnails))
        assertEquals(76, hd.thumbnails.last().quality)
        assertEquals(PlannedImage(1100, 825, 80), hd.finalImage)

        val original = planMediaImages(ImageSize(1100, 825), MediaQuality.ORIGINAL)
        assertEquals(listOf("320x240", "640x480", "1100x825"), sizes(original.thumbnails))
        assertEquals(80, original.thumbnails.last().quality)
    }

    @Test
    fun workedExample_1000x750() {
        val thumbs = listOf("320x240", "640x480", "1000x750")
        val standard = planMediaImages(ImageSize(1000, 750), MediaQuality.STANDARD)
        assertEquals(thumbs, sizes(standard.thumbnails))
        assertEquals(PlannedImage(1000, 750, 70), standard.finalImage)
        val hd = planMediaImages(ImageSize(1000, 750), MediaQuality.HD)
        assertEquals(thumbs, sizes(hd.thumbnails))
        assertEquals(PlannedImage(1000, 750, 80), hd.finalImage)
        assertPlan(1000, 750, MediaQuality.ORIGINAL, thumbs, null)
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
        repeat(50_000) {
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
                assertTrue(max(plan.tiny.width, plan.tiny.height) <= 20)
                assertTrue(min(plan.tiny.width, plan.tiny.height) >= 1)
            }
        }
    }
}
