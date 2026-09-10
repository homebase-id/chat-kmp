package id.homebase.core.audio

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingAudioPlayer : AudioPlayer {
    val calls = mutableListOf<String>()
    var observer: AudioPlaybackObserver? = null
    var appliedSpeed = 1f

    override fun play(filePath: String) { calls += "play:$filePath" }
    override fun jumpTo(positionMs: Long) { calls += "jumpTo:$positionMs" }
    override fun resume() { calls += "resume" }
    override fun pause() { calls += "pause" }
    override fun stop() { calls += "stop" }
    override fun release() { calls += "release" }
    override fun setSpeed(speed: Float) {
        appliedSpeed = speed
        calls += "setSpeed:$speed"
    }

    override fun setPlaybackObserver(observer: AudioPlaybackObserver) {
        this.observer = observer
    }
}

private class RecordingProximityRouter : ProximityAudioRouter {
    override val isNearEar = kotlinx.coroutines.flow.MutableStateFlow(false)
    var startCount = 0
    var stopCount = 0
    override fun start() { startCount++ }
    override fun stop() { stopCount++ }
}

class VoiceNotePlaybackTest {

    private fun playback(
        player: AudioPlayer,
        router: ProximityAudioRouter = RecordingProximityRouter(),
        scope: TestScope,
    ) = DefaultVoiceNotePlayback(player, router, scope)

    @Test
    fun playingASecondKeyStopsTheFirst() = runTest(StandardTestDispatcher()) {
        val player = RecordingAudioPlayer()
        val service = playback(player, scope = this)

        service.play("bubble-a", "/tmp/a.m4a")
        assertEquals("bubble-a", service.state.value.playingKey)
        assertTrue(service.state.value.isPlaying)

        player.observer?.onProgressUpdate(4_000, 9_000)
        assertEquals(4_000L, service.state.value.positionMs)

        service.play("bubble-b", "/tmp/b.m4a")

        assertEquals("bubble-b", service.state.value.playingKey)
        // The first note's position must not bleed into the second bubble.
        assertEquals(0L, service.state.value.positionMs)
        assertEquals(
            listOf("stop", "play:/tmp/a.m4a", "setSpeed:1.0", "stop", "play:/tmp/b.m4a", "setSpeed:1.0"),
            player.calls,
        )
    }

    @Test
    fun speedPersistsAcrossKeys() = runTest(StandardTestDispatcher()) {
        val player = RecordingAudioPlayer()
        val service = playback(player, scope = this)

        service.play("bubble-a", "/tmp/a.m4a")
        service.setSpeed(1.5f)
        testScheduler.advanceUntilIdle()
        assertEquals(1.5f, player.appliedSpeed)

        service.play("bubble-b", "/tmp/b.m4a")
        assertEquals(1.5f, service.state.value.speed)
        assertEquals(1.5f, player.appliedSpeed)
    }

    @Test
    fun speedIsClampedToTheSupportedRange() = runTest(StandardTestDispatcher()) {
        val player = RecordingAudioPlayer()
        val service = playback(player, scope = this)

        service.setSpeed(9f)
        assertEquals(MAX_PLAYBACK_SPEED, service.state.value.speed)

        service.setSpeed(0.01f)
        assertEquals(MIN_PLAYBACK_SPEED, service.state.value.speed)
    }

    @Test
    fun completionRewindsWithoutClearingTheOwningBubble() = runTest(StandardTestDispatcher()) {
        val player = RecordingAudioPlayer()
        val service = playback(player, scope = this)

        service.play("bubble-a", "/tmp/a.m4a")
        player.observer?.onProgressUpdate(8_500, 9_000)
        player.observer?.onComplete()

        assertEquals("bubble-a", service.state.value.playingKey)
        assertEquals(0L, service.state.value.positionMs)
        assertFalse(service.state.value.isPlaying)
    }

    @Test
    fun stopClearsTheOwningBubble() = runTest(StandardTestDispatcher()) {
        val player = RecordingAudioPlayer()
        val service = playback(player, scope = this)

        service.play("bubble-a", "/tmp/a.m4a")
        service.stop()

        assertNull(service.state.value.playingKey)
        assertFalse(service.state.value.isPlaying)
    }

    @Test
    fun seekingConvertsTheFractionAgainstTheReportedDuration() = runTest(StandardTestDispatcher()) {
        val player = RecordingAudioPlayer()
        val service = playback(player, scope = this)

        service.play("bubble-a", "/tmp/a.m4a")
        player.observer?.onProgressUpdate(0, 10_000)
        service.seekTo(0.25f)
        testScheduler.advanceUntilIdle()

        assertEquals(2_500L, service.state.value.positionMs)
        assertTrue(player.calls.contains("jumpTo:2500"))
    }

    @Test
    fun proximityMonitoringFollowsPlayback() = runTest(StandardTestDispatcher()) {
        val player = RecordingAudioPlayer()
        val router = RecordingProximityRouter()
        val service = playback(player, router, this)

        service.play("bubble-a", "/tmp/a.m4a")
        assertEquals(1, router.startCount)

        service.pause()
        assertEquals(1, router.stopCount)

        service.resume()
        assertEquals(2, router.startCount)

        player.observer?.onComplete()
        assertEquals(2, router.stopCount)
    }

    @Test
    fun progressForAStoppedServiceIsDropped() = runTest(StandardTestDispatcher()) {
        val player = RecordingAudioPlayer()
        val service = playback(player, scope = this)

        service.play("bubble-a", "/tmp/a.m4a")
        service.stop()
        player.observer?.onProgressUpdate(3_000, 9_000)

        assertEquals(0L, service.state.value.positionMs)
        assertEquals(0L, service.state.value.durationMs)
    }
}
