package id.homebase.api.video

import kotlin.test.Test
import kotlin.test.assertEquals

class FullPosterLongEdgeTest {

    @Test
    fun aFrameAtOrUnder4096KeepsItsSize() {
        assertEquals(1920, VideoThumbnailQuality.fullPosterLongEdge(1920, 1080))
        assertEquals(3840, VideoThumbnailQuality.fullPosterLongEdge(2160, 3840))
    }

    @Test
    fun an8kFrameShrinksTo4096() {
        assertEquals(4096, VideoThumbnailQuality.fullPosterLongEdge(7680, 4320))
        assertEquals(4096, VideoThumbnailQuality.fullPosterLongEdge(4320, 7680))
    }

    // Original's 2048 row keeps a 1080 short edge, so a wide frame keeps twice that.
    @Test
    fun aWideFrameKeepsA2160ShortEdge() {
        assertEquals(7680, VideoThumbnailQuality.fullPosterLongEdge(7680, 1080))
        assertEquals(7680, VideoThumbnailQuality.fullPosterLongEdge(15360, 4320))
    }
}
