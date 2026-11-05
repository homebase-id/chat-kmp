package id.homebase.core.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioPlayerContractTest {

    @Test
    fun controlsAreNoOpsWhenNothingIsLoaded() {
        val player = getAudioPlayer()
        player.jumpTo(1_000)
        player.resume()
        player.pause()
        player.stop()
        player.setSpeed(1.5f)
        player.release()
        player.release()
    }

    @Test
    fun playingAMissingFileCompletesInsteadOfHanging() = runTest {
        val player = getAudioPlayer()
        val completed = CompletableDeferred<Unit>()
        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() {
                completed.complete(Unit)
            }

            override fun onProgressUpdate(positionMs: Long, durationMs: Long) = Unit
        })
        try {
            player.play("/no/such/dir/missing.m4a")
            withContext(Dispatchers.Default) { withTimeout(15_000) { completed.await() } }
        } finally {
            player.release()
        }
    }

    @Test
    fun speedIsClampedToTheSupportedRange() {
        assertEquals(MIN_PLAYBACK_SPEED, 0.1f.coerceToPlaybackSpeed())
        assertEquals(MAX_PLAYBACK_SPEED, 9f.coerceToPlaybackSpeed())
        assertEquals(1.25f, 1.25f.coerceToPlaybackSpeed())
    }
}
