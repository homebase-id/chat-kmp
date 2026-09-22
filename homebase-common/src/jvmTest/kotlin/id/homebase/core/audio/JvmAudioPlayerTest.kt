package id.homebase.core.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fake SourceDataLine that discards audio and tracks position via bytes written.
 */
private class FakeSourceDataLine(
    private val format: AudioFormat,
    private val simulateRealtime: Boolean = false,
) : StubSourceDataLine() {
    @Volatile var totalBytesWritten = 0L
    @Volatile var started = false
    @Volatile var stopped = false
    @Volatile var closed = false
    @Volatile var drained = false

    override fun open(format: AudioFormat, bufferSize: Int) {}
    override fun start() { started = true; stopped = false }
    override fun stop() { stopped = true; started = false }
    override fun close() { closed = true }
    override fun drain() { drained = true }
    override fun write(b: ByteArray, off: Int, len: Int): Int {
        totalBytesWritten += len
        if (simulateRealtime) Thread.sleep(10)
        return len
    }
    override fun getMicrosecondPosition(): Long {
        val bytesPerSecond = format.sampleRate * format.channels * (format.sampleSizeInBits / 8)
        return (totalBytesWritten * 1_000_000L / bytesPerSecond.toLong())
    }
    override fun getFormat(): AudioFormat = format
}

/**
 * Test subclass that bypasses FFmpeg and audio hardware.
 */
private class TestableAudioPlayer(
    private val fakeDurationMs: Long = 30_000,
    private val fakeAudioBytes: ByteArray = ByteArray(44100 * 2 * 2), // 1 second of stereo 16-bit
) : JvmAudioPlayer() {

    var lastSeekMs: Long? = null
    var decoderStartCount = 0
    var fakeLine: FakeSourceDataLine? = null
    var simulateRealtime = false

    override fun startDecoder(filePath: String, seekMs: Long): InputStream {
        lastSeekMs = seekMs
        decoderStartCount++
        return ByteArrayInputStream(fakeAudioBytes)
    }

    override fun openAudioLine(format: AudioFormat): SourceDataLine {
        val line = FakeSourceDataLine(format, simulateRealtime)
        fakeLine = line
        return line
    }

    override fun probeDurationMs(filePath: String): Long = fakeDurationMs

    override fun ffmpegExecutable(): String = "/fake/bin/ffmpeg"
}

class JvmAudioPlayerTest {

    @Test
    fun playSetsDurationAndStartsDecoder() {
        val player = TestableAudioPlayer(fakeDurationMs = 45_000)
        player.play("/fake/audio.m4a")
        Thread.sleep(100) // let threads start

        assertEquals(45_000L, player.totalDurationMs)
        assertEquals(0L, player.seekOffsetMs)
        assertEquals(1, player.decoderStartCount)
        assertEquals(0L, player.lastSeekMs)

        player.release()
    }

    @Test
    fun stopResetsSeekOffset() {
        val player = TestableAudioPlayer(fakeDurationMs = 60_000)
        player.play("/fake/audio.wav")
        Thread.sleep(50)

        player.jumpTo(20_000)
        Thread.sleep(50)
        assertEquals(20_000L, player.seekOffsetMs)

        player.stop()
        assertEquals(0L, player.seekOffsetMs)

        player.release()
    }

    @Test
    fun jumpClampsToTotalDuration() {
        val player = TestableAudioPlayer(fakeDurationMs = 30_000)
        player.play("/fake/audio.wav")
        Thread.sleep(50)

        player.jumpTo(100_000)
        Thread.sleep(50)
        assertEquals(30_000L, player.seekOffsetMs)

        player.jumpTo(-5_000)
        Thread.sleep(50)
        assertEquals(0L, player.seekOffsetMs)

        player.release()
    }

    @Test
    fun jumpRestartsDecoder() {
        val player = TestableAudioPlayer(fakeDurationMs = 60_000)
        player.play("/fake/audio.wav")
        Thread.sleep(50)
        assertEquals(1, player.decoderStartCount)

        player.jumpTo(15_000)
        Thread.sleep(50)
        assertEquals(2, player.decoderStartCount)
        assertEquals(15_000L, player.lastSeekMs)

        player.release()
    }

    @Test
    fun jumpDoesNothingWithoutPriorPlay() {
        val player = TestableAudioPlayer()
        player.jumpTo(10_000)
        assertEquals(0, player.decoderStartCount)
        assertNull(player.lastSeekMs)
    }

    @Test
    fun releaseResetsState() {
        val player = TestableAudioPlayer()
        val observer = object : AudioPlaybackObserver {
            override fun onComplete() {}
            override fun onProgressUpdate(positionMs: Long, durationMs: Long) {}
        }
        player.setPlaybackObserver(observer)
        player.play("/fake/audio.wav")
        Thread.sleep(50)

        player.release()
        assertEquals(0L, player.seekOffsetMs)
    }

    @Test
    fun observerReceivesOnComplete() {
        val shortAudio = ByteArray(1764) // ~10ms of stereo 16-bit at 44100
        val completed = CountDownLatch(1)
        val player = TestableAudioPlayer(fakeDurationMs = 1_000, fakeAudioBytes = shortAudio)

        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() { completed.countDown() }
            override fun onProgressUpdate(positionMs: Long, durationMs: Long) {}
        })

        player.play("/fake/short.wav")
        assertTrue(completed.await(3, TimeUnit.SECONDS), "onComplete should fire after stream ends")
        player.release()
    }

    @Test
    fun observerReceivesProgressUpdates() {
        val audio = ByteArray(44100 * 2 * 2 * 2) // ~2 seconds
        val progressLatch = CountDownLatch(1)
        val lastTotal = AtomicLong(0)

        val player = TestableAudioPlayer(fakeDurationMs = 2_000, fakeAudioBytes = audio)
        player.simulateRealtime = true
        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() {}
            override fun onProgressUpdate(positionMs: Long, durationMs: Long) {
                lastTotal.set(durationMs)
                progressLatch.countDown()
            }
        })

        player.play("/fake/audio.wav")
        assertTrue(progressLatch.await(3, TimeUnit.SECONDS), "Observer should receive progress updates")
        assertEquals(2_000L, lastTotal.get())
        player.release()
    }

    @Test
    fun progressIsReportedInMilliseconds() {
        val audio = ByteArray(44100 * 2 * 2 * 2) // ~2 seconds
        val sawSubSecond = CountDownLatch(1)
        val player = TestableAudioPlayer(fakeDurationMs = 2_000, fakeAudioBytes = audio)
        player.simulateRealtime = true
        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() {}
            override fun onProgressUpdate(positionMs: Long, durationMs: Long) {
                // A whole-second reporter can only ever emit 0 or a multiple of 1000.
                if (positionMs > 0 && positionMs % 1000L != 0L) sawSubSecond.countDown()
            }
        })

        player.play("/fake/audio.wav")
        assertTrue(
            sawSubSecond.await(3, TimeUnit.SECONDS),
            "Progress should carry millisecond resolution",
        )
        player.release()
    }

    @Test
    fun progressClampsToTotalDuration() {
        val audio = ByteArray(44100 * 2 * 2 * 3) // ~3 seconds
        val maxProgress = AtomicLong(0)
        val progressLatch = CountDownLatch(2)
        val player = TestableAudioPlayer(fakeDurationMs = 1_000, fakeAudioBytes = audio)
        player.simulateRealtime = true
        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() {}
            override fun onProgressUpdate(positionMs: Long, durationMs: Long) {
                if (positionMs > maxProgress.get()) maxProgress.set(positionMs)
                progressLatch.countDown()
            }
        })

        player.play("/fake/audio.wav")
        progressLatch.await(3, TimeUnit.SECONDS)

        assertTrue(maxProgress.get() <= 1_000L, "Progress should not exceed total duration")
        player.release()
    }

    @Test
    fun multiplePlayCallsRestartCleanly() {
        val player = TestableAudioPlayer(fakeDurationMs = 30_000)

        player.play("/fake/first.wav")
        Thread.sleep(50)
        assertEquals(1, player.decoderStartCount)

        player.play("/fake/second.wav")
        Thread.sleep(50)
        assertEquals(2, player.decoderStartCount)
        assertEquals(0L, player.lastSeekMs)

        player.release()
    }

    @Test
    fun pauseStopsAudioLine() {
        val player = TestableAudioPlayer()
        player.play("/fake/audio.wav")
        Thread.sleep(50)

        player.pause()
        Thread.sleep(50)
        val line = player.fakeLine
        assertTrue(line?.stopped == true, "Audio line should be stopped after pause")

        player.release()
    }

    @Test
    fun resumeRestartsAudioLine() {
        val player = TestableAudioPlayer()
        player.play("/fake/audio.wav")
        Thread.sleep(50)

        player.pause()
        Thread.sleep(50)
        player.resume()
        Thread.sleep(50)
        val line = player.fakeLine
        assertTrue(line?.started == true, "Audio line should be started after resume")

        player.release()
    }

    @Test
    fun estimateDurationFromFileSizeReturnsAtLeastOne() {
        val temp = File.createTempFile("test_audio", ".raw")
        try {
            temp.writeBytes(ByteArray(100))
            val duration = JvmAudioPlayer.estimateDurationFromFileSize(temp.absolutePath)
            assertEquals(1L, duration, "Very small files should return at least 1 millisecond")
        } finally {
            temp.delete()
        }
    }

    @Test
    fun estimateDurationFromFileSizeCalculatesCorrectly() {
        val temp = File.createTempFile("test_audio", ".raw")
        try {
            // 5 seconds of 44100 Hz, stereo, 16-bit = 44100 * 2 * 2 * 5 = 882000 bytes
            temp.writeBytes(ByteArray(882000))
            val duration = JvmAudioPlayer.estimateDurationFromFileSize(temp.absolutePath)
            assertEquals(5_000L, duration)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun backwardsProgressIsIgnoredUntilItPersists() {
        val player = TestableAudioPlayer(fakeDurationMs = 60_000)

        assertEquals(5_000L, player.monotonicPositionMs(5_000))

        // Three isolated dips are jitter from the line restarting its own clock.
        assertEquals(5_000L, player.monotonicPositionMs(400))
        assertEquals(5_000L, player.monotonicPositionMs(400))
        assertEquals(5_000L, player.monotonicPositionMs(400))
        // The fourth in a row is a real rewind.
        assertEquals(400L, player.monotonicPositionMs(400))
    }

    @Test
    fun forwardProgressResetsTheBackwardsCounter() {
        val player = TestableAudioPlayer(fakeDurationMs = 60_000)

        player.monotonicPositionMs(5_000)
        player.monotonicPositionMs(400)
        player.monotonicPositionMs(400)
        assertEquals(6_000L, player.monotonicPositionMs(6_000))

        // Counter restarted, so three dips are absorbed again.
        assertEquals(6_000L, player.monotonicPositionMs(400))
        assertEquals(6_000L, player.monotonicPositionMs(400))
        assertEquals(6_000L, player.monotonicPositionMs(400))
    }

    @Test
    fun ffmpegCommandOmitsAtempoAtNormalSpeed() {
        val player = TestableAudioPlayer()
        val command = player.buildFfmpegCommand("/fake/audio.m4a", seekMs = 0, speed = 1f)
        assertFalse(command.any { it.startsWith("atempo") }, "1x needs no filter graph")
        assertFalse(command.contains("-filter:a"))
    }

    @Test
    fun ffmpegCommandCarriesAtempoForFasterSpeeds() {
        val player = TestableAudioPlayer()

        val oneAndAHalf = player.buildFfmpegCommand("/fake/audio.m4a", seekMs = 0, speed = 1.5f)
        assertEquals("atempo=1.5", oneAndAHalf[oneAndAHalf.indexOf("-filter:a") + 1])

        val double = player.buildFfmpegCommand("/fake/audio.m4a", seekMs = 0, speed = 2f)
        assertEquals("atempo=2.0", double[double.indexOf("-filter:a") + 1])
    }

    @Test
    fun ffmpegCommandClampsSpeedToTheAtempoRange() {
        val player = TestableAudioPlayer()

        val tooFast = player.buildFfmpegCommand("/fake/audio.m4a", seekMs = 0, speed = 8f)
        assertEquals("atempo=2.0", tooFast[tooFast.indexOf("-filter:a") + 1])

        val tooSlow = player.buildFfmpegCommand("/fake/audio.m4a", seekMs = 0, speed = 0.1f)
        assertEquals("atempo=0.5", tooSlow[tooSlow.indexOf("-filter:a") + 1])
    }

    @Test
    fun ffmpegCommandSeeksWithSubSecondPrecision() {
        val player = TestableAudioPlayer()
        val command = player.buildFfmpegCommand("/fake/audio.m4a", seekMs = 1_500)
        assertEquals("1.5", command[command.indexOf("-ss") + 1])
    }

    @Test
    fun setSpeedRestartsTheDecoderAtTheCurrentPosition() {
        val player = TestableAudioPlayer(fakeDurationMs = 60_000)
        player.play("/fake/audio.wav")
        Thread.sleep(50)
        assertEquals(1, player.decoderStartCount)

        player.monotonicPositionMs(12_000)
        player.setSpeed(1.5f)
        Thread.sleep(50)

        assertEquals(2, player.decoderStartCount)
        assertEquals(12_000L, player.lastSeekMs)
        assertEquals(1.5f, player.speed)

        player.release()
    }

    @Test
    fun setSpeedBeforePlayIsRememberedForTheFirstDecoder() {
        val player = TestableAudioPlayer(fakeDurationMs = 60_000)
        player.setSpeed(2f)
        assertEquals(0, player.decoderStartCount)
        assertEquals(2f, player.speed)

        player.play("/fake/audio.wav")
        Thread.sleep(50)

        val command = player.buildFfmpegCommand("/fake/audio.wav", seekMs = 0, speed = player.speed)
        assertEquals("atempo=2.0", command[command.indexOf("-filter:a") + 1])

        player.release()
    }
}

/**
 * Minimal stub implementing SourceDataLine with no-ops.
 * Only methods used by tests are overridden in FakeSourceDataLine.
 */
private abstract class StubSourceDataLine : SourceDataLine {
    override fun open(format: AudioFormat, bufferSize: Int) {}
    override fun open(format: AudioFormat) {}
    override fun open() {}
    override fun write(b: ByteArray, off: Int, len: Int): Int = len
    override fun drain() {}
    override fun flush() {}
    override fun start() {}
    override fun stop() {}
    override fun isRunning(): Boolean = false
    override fun isActive(): Boolean = false
    override fun getFormat(): AudioFormat = AudioFormat(44100f, 16, 2, true, false)
    override fun getBufferSize(): Int = 8192
    override fun available(): Int = 0
    override fun getFramePosition(): Int = 0
    override fun getLongFramePosition(): Long = 0
    override fun getMicrosecondPosition(): Long = 0
    override fun getLevel(): Float = 0f
    override fun getLineInfo(): javax.sound.sampled.Line.Info = javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, AudioFormat(44100f, 16, 2, true, false))
    override fun close() {}
    override fun isOpen(): Boolean = true
    override fun getControls(): Array<javax.sound.sampled.Control> = emptyArray()
    override fun isControlSupported(control: javax.sound.sampled.Control.Type): Boolean = false
    override fun getControl(control: javax.sound.sampled.Control.Type): javax.sound.sampled.Control = throw IllegalArgumentException()
    override fun addLineListener(listener: javax.sound.sampled.LineListener) {}
    override fun removeLineListener(listener: javax.sound.sampled.LineListener) {}
}
