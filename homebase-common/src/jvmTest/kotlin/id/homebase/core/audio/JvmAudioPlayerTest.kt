package id.homebase.core.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
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
    private val fakeDuration: Int = 30,
    private val fakeAudioBytes: ByteArray = ByteArray(44100 * 2 * 2), // 1 second of stereo 16-bit
) : JvmAudioPlayer() {

    var lastSeekSeconds: Int? = null
    var decoderStartCount = 0
    var fakeLine: FakeSourceDataLine? = null
    var simulateRealtime = false

    override fun startDecoder(filePath: String, seekSeconds: Int): InputStream {
        lastSeekSeconds = seekSeconds
        decoderStartCount++
        return ByteArrayInputStream(fakeAudioBytes)
    }

    override fun openAudioLine(format: AudioFormat): SourceDataLine {
        val line = FakeSourceDataLine(format, simulateRealtime)
        fakeLine = line
        return line
    }

    override fun probeDurationSeconds(filePath: String): Int = fakeDuration
}

class JvmAudioPlayerTest {

    @Test
    fun playSetsDurationAndStartsDecoder() {
        val player = TestableAudioPlayer(fakeDuration = 45)
        player.play("/fake/audio.m4a")
        Thread.sleep(100) // let threads start

        assertEquals(45, player.totalDurationSeconds)
        assertEquals(0, player.seekOffsetSeconds)
        assertEquals(1, player.decoderStartCount)
        assertEquals(0, player.lastSeekSeconds)

        player.release()
    }

    @Test
    fun stopResetsSeekOffset() {
        val player = TestableAudioPlayer(fakeDuration = 60)
        player.play("/fake/audio.wav")
        Thread.sleep(50)

        player.jump(20)
        Thread.sleep(50)
        assertEquals(20, player.seekOffsetSeconds)

        player.stop()
        assertEquals(0, player.seekOffsetSeconds)

        player.release()
    }

    @Test
    fun jumpClampsToTotalDuration() {
        val player = TestableAudioPlayer(fakeDuration = 30)
        player.play("/fake/audio.wav")
        Thread.sleep(50)

        player.jump(100)
        Thread.sleep(50)
        assertEquals(30, player.seekOffsetSeconds)

        player.jump(-5)
        Thread.sleep(50)
        assertEquals(0, player.seekOffsetSeconds)

        player.release()
    }

    @Test
    fun jumpRestartsDecoder() {
        val player = TestableAudioPlayer(fakeDuration = 60)
        player.play("/fake/audio.wav")
        Thread.sleep(50)
        assertEquals(1, player.decoderStartCount)

        player.jump(15)
        Thread.sleep(50)
        assertEquals(2, player.decoderStartCount)
        assertEquals(15, player.lastSeekSeconds)

        player.release()
    }

    @Test
    fun jumpDoesNothingWithoutPriorPlay() {
        val player = TestableAudioPlayer()
        player.jump(10)
        assertEquals(0, player.decoderStartCount)
        assertNull(player.lastSeekSeconds)
    }

    @Test
    fun releaseResetsState() {
        val player = TestableAudioPlayer()
        val observer = object : AudioPlaybackObserver {
            override fun onComplete() {}
            override fun onProgressUpdate(progressSeconds: Int, totalSeconds: Int) {}
        }
        player.setPlaybackObserver(observer)
        player.play("/fake/audio.wav")
        Thread.sleep(50)

        player.release()
        assertEquals(0, player.seekOffsetSeconds)
    }

    @Test
    fun observerReceivesOnComplete() {
        val shortAudio = ByteArray(1764) // ~10ms of stereo 16-bit at 44100
        val completed = CountDownLatch(1)
        val player = TestableAudioPlayer(fakeDuration = 1, fakeAudioBytes = shortAudio)

        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() { completed.countDown() }
            override fun onProgressUpdate(progressSeconds: Int, totalSeconds: Int) {}
        })

        player.play("/fake/short.wav")
        assertTrue(completed.await(3, TimeUnit.SECONDS), "onComplete should fire after stream ends")
        player.release()
    }

    @Test
    fun observerReceivesProgressUpdates() {
        val audio = ByteArray(44100 * 2 * 2 * 2) // ~2 seconds
        val progressLatch = CountDownLatch(1)
        val lastTotal = AtomicInteger(0)

        val player = TestableAudioPlayer(fakeDuration = 2, fakeAudioBytes = audio)
        player.simulateRealtime = true
        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() {}
            override fun onProgressUpdate(progressSeconds: Int, totalSeconds: Int) {
                lastTotal.set(totalSeconds)
                progressLatch.countDown()
            }
        })

        player.play("/fake/audio.wav")
        assertTrue(progressLatch.await(3, TimeUnit.SECONDS), "Observer should receive progress updates")
        assertEquals(2, lastTotal.get())
        player.release()
    }

    @Test
    fun progressClampsToTotalDuration() {
        val audio = ByteArray(44100 * 2 * 2 * 3) // ~3 seconds
        val maxProgress = AtomicInteger(0)
        val progressLatch = CountDownLatch(2)
        val player = TestableAudioPlayer(fakeDuration = 1, fakeAudioBytes = audio)
        player.simulateRealtime = true
        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() {}
            override fun onProgressUpdate(progressSeconds: Int, totalSeconds: Int) {
                if (progressSeconds > maxProgress.get()) maxProgress.set(progressSeconds)
                progressLatch.countDown()
            }
        })

        player.play("/fake/audio.wav")
        progressLatch.await(3, TimeUnit.SECONDS)

        assertTrue(maxProgress.get() <= 1, "Progress should not exceed total duration")
        player.release()
    }

    @Test
    fun multiplePlayCallsRestartCleanly() {
        val player = TestableAudioPlayer(fakeDuration = 30)

        player.play("/fake/first.wav")
        Thread.sleep(50)
        assertEquals(1, player.decoderStartCount)

        player.play("/fake/second.wav")
        Thread.sleep(50)
        assertEquals(2, player.decoderStartCount)
        assertEquals(0, player.lastSeekSeconds)

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
            assertEquals(1, duration, "Very small files should return at least 1 second")
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
            assertEquals(5, duration)
        } finally {
            temp.delete()
        }
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
