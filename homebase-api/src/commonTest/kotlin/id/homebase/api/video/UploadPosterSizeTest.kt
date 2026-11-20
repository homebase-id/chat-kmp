package id.homebase.api.video

import id.homebase.api.image.ImageHeaderParser
import id.homebase.api.image.ImageSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** On each platform's real decoder; skips where there's none to run (Android host tests, and the JVM, whose test classpath has no ffmpeg). */
class UploadPosterSizeTest {

    @Test
    fun anUploadsPosterOfA5kVideoIs4096Wide() = runTest {
        val path = stagePosterVideoForTest() ?: return@runTest
        try {
            val poster = assertNotNull(VideoThumbnailService.extractPosterFrame(path, fullSize = true))
            assertEquals(ImageSize(4096, 2304), ImageHeaderParser.parse(poster))
        } finally {
            cleanupStagedSampleVideo(path)
        }
    }
}
