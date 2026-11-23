package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionPlaybackTest {
    @Test
    fun aClipShowsFromItsFirstFrameAndHandsBackOnce() {
        val playback = MotionPlayback(play = true)
        assertTrue(playback.playing)
        assertFalse(playback.visible, "the still shows while the clip loads")
        playback.onFirstFrame()
        assertTrue(playback.visible)
        playback.stop()
        assertFalse(playback.playing || playback.visible)
        playback.onFirstFrame()
        assertFalse(playback.visible, "a late first frame after a touch doesn't bring it back")
        assertFalse(MotionPlayback(play = false).playing, "a neighbouring page doesn't play")
    }
}
