package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals

// THUMBNAILS.md's appendix: change the two together.
class ThumbnailLadderTest {

    private val standard = mapOf(
        ImageSize(3072, 4080) to listOf("241x320", "482x640", "813x1080"), // Pixel 8, 12.5 MP 4:3
        ImageSize(2268, 4032) to listOf("240x427", "480x854"), // Pixel 9 MP / iPhone 12 MP 16:9
        ImageSize(6144, 8160) to listOf("241x320", "482x640", "813x1080"), // Pixel 8 Pro, 50 MP
        ImageSize(3024, 4032) to listOf("240x320", "480x640", "810x1080"), // iPhone 12 MP 4:3
        ImageSize(4284, 5712) to listOf("240x320", "480x640", "810x1080"), // iPhone 24 MP
        ImageSize(6048, 8064) to listOf("240x320", "480x640", "810x1080"), // iPhone 48 MP
        ImageSize(3000, 4000) to listOf("240x320", "480x640", "810x1080"), // Galaxy S23 12 MP
        ImageSize(12240, 16320) to listOf("240x320", "480x640", "810x1080"), // Galaxy S23 Ultra 200 MP
        ImageSize(3024, 3024) to listOf("320x320", "640x640", "1080x1080"), // square
        ImageSize(6000, 4000) to listOf("360x240", "720x480", "1215x810"), // DSLR 24 MP 3:2
        ImageSize(9546, 1619) to listOf("640x109", "1280x217", "2160x366"), // Pixel panorama
        ImageSize(1080, 2400) to listOf("240x534", "480x1067"), // phone screenshot
        ImageSize(2560, 1440) to listOf("427x240", "854x480"), // desktop screenshot
        ImageSize(1600, 1200) to listOf("320x240", "640x480"), // small photo
    )

    private val hd = mapOf(
        ImageSize(3072, 4080) to listOf("241x320", "482x640", "813x1080", "1542x2048"),
        ImageSize(2268, 4032) to listOf("240x427", "480x854", "1152x2048"),
        ImageSize(6048, 8064) to listOf("240x320", "480x640", "810x1080", "1536x2048"),
        ImageSize(3024, 3024) to listOf("320x320", "640x640", "1080x1080"), // 2048x2048 is 1.48x below 3024
        ImageSize(6000, 4000) to listOf("360x240", "720x480", "1215x810", "2048x1365"),
        ImageSize(9546, 1619) to listOf("640x109", "1280x217", "2160x366"), // 6368x1080 is 1.29x below 8192
        ImageSize(1080, 2400) to listOf("240x534", "480x1067"), // 1080x2400 is the final image's size
        ImageSize(2560, 1440) to listOf("427x240", "854x480", "1440x810"),
        ImageSize(1600, 1200) to listOf("320x240", "640x480"),
    )

    private val original = mapOf(
        ImageSize(3072, 4080) to listOf("241x320", "482x640", "813x1080", "1542x2048"),
        ImageSize(2268, 4032) to listOf("240x427", "480x854", "1152x2048"),
        ImageSize(3024, 4032) to listOf("240x320", "480x640", "810x1080", "1536x2048"),
        ImageSize(3024, 3024) to listOf("320x320", "640x640", "1080x1080", "2048x2048"),
        ImageSize(6000, 4000) to listOf("360x240", "720x480", "1215x810", "2048x1365"),
        ImageSize(9546, 1619) to listOf("640x109", "1280x217", "2160x366", "6368x1080"),
        ImageSize(1080, 2400) to listOf("240x534", "480x1067", "1080x2400"),
        ImageSize(2560, 1440) to listOf("427x240", "854x480", "2048x1152"),
        ImageSize(1600, 1200) to listOf("320x240", "640x480", "1600x1200"),
    )

    @Test
    fun everyKeptThumbnailIsAStepBelowTheNextImage() {
        for ((setting, table) in mapOf(MediaQuality.STANDARD to standard, MediaQuality.HD to hd, MediaQuality.ORIGINAL to original)) {
            for ((source, expected) in table) {
                assertEquals(expected, sizes(planMediaImages(source, setting).thumbnails), "$source $setting")
            }
        }
    }

    @Test
    fun aFileKeptAsThePayloadKeepsItsLargestThumbnail() {
        val nineBySixteen = ImageSize(2268, 4032)
        val plan = planMediaImages(nineBySixteen, MediaQuality.STANDARD, makesFinalImage = false)
        assertEquals(listOf("240x427", "480x854", "810x1440"), sizes(plan.thumbnails))
        assertEquals(null, plan.finalImage)
    }
}
