package id.homebase.core.audio

import co.touchlab.kermit.Logger
import id.homebase.api.video.FFmpegBinaryManager
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

open class JvmAudioPlayer : AudioPlayer {
    private var process: Process? = null
    private var sourceLine: SourceDataLine? = null
    private var playbackThread: Thread? = null
    private var progressThread: Thread? = null
    private var observer: AudioPlaybackObserver? = null

    private var currentFilePath: String? = null

    @Volatile
    internal var totalDurationMs: Long = 0
        private set

    @Volatile
    internal var seekOffsetMs: Long = 0
        private set

    @Volatile
    internal var speed: Float = 1f
        private set

    @Volatile
    private var lastReportedMs: Long = 0

    @Volatile
    private var backwardsCount = 0

    @Volatile
    private var isPaused = false

    @Volatile
    private var isStopped = true

    override fun play(filePath: String) {
        stopPlayback()
        currentFilePath = filePath
        seekOffsetMs = 0
        isStopped = false
        isPaused = false
        totalDurationMs = probeDurationMs(filePath)
        startPlayback(filePath, seekMs = 0)
    }

    override fun pause() {
        isPaused = true
        sourceLine?.stop()
    }

    override fun resume() {
        sourceLine?.start()
        isPaused = false
    }

    override fun jumpTo(positionMs: Long) {
        val path = currentFilePath ?: return
        val clamped = positionMs.coerceIn(0, totalDurationMs)
        stopPlayback()
        seekOffsetMs = clamped
        isStopped = false
        isPaused = false
        startPlayback(path, seekMs = clamped)
    }

    override fun stop() {
        isStopped = true
        stopPlayback()
        seekOffsetMs = 0
    }

    // ffmpeg resamples the whole stream, so a speed change has to restart the decoder from
    // wherever the line had reached.
    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceToPlaybackSpeed()
        if (clamped == this.speed) return
        val path = currentFilePath
        val resumeAt = lastReportedMs
        this.speed = clamped
        if (path == null || isStopped) return
        stopPlayback()
        seekOffsetMs = resumeAt.coerceIn(0, totalDurationMs)
        isStopped = false
        startPlayback(path, seekMs = seekOffsetMs)
    }

    override fun release() {
        stop()
        observer = null
        currentFilePath = null
    }

    override fun setPlaybackObserver(observer: AudioPlaybackObserver) {
        this.observer = observer
    }

    internal fun buildFfmpegCommand(
        filePath: String,
        seekMs: Long,
        speed: Float = 1f,
    ): List<String> = buildList {
        add(ffmpegExecutable())
        add("-v"); add("error")
        if (seekMs > 0) {
            add("-ss"); add((seekMs / 1000.0).toString())
        }
        add("-i"); add(filePath)
        // A single atempo stage covers the clamped 0.5-2.0 range; beyond it ffmpeg needs a chain.
        val tempo = speed.coerceToPlaybackSpeed()
        if (tempo != 1f) {
            add("-filter:a"); add("atempo=$tempo")
        }
        add("-f"); add("s16le")
        add("-acodec"); add("pcm_s16le")
        add("-ar"); add(SAMPLE_RATE.toString())
        add("-ac"); add(CHANNELS.toString())
        add("pipe:1")
    }

    protected open fun ffmpegExecutable(): String = FFmpegBinaryManager.ffmpegPath()

    protected open fun startDecoder(filePath: String, seekMs: Long): InputStream? {
        if (!FFmpegBinaryManager.isAvailable()) {
            Logger.e(tag = TAG) { "FFmpeg binaries not available — cannot decode audio" }
            return null
        }
        val command = buildFfmpegCommand(filePath, seekMs, speed)
        process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        drainStderr(process!!)
        return process?.inputStream
    }

    protected open fun openAudioLine(format: AudioFormat): SourceDataLine {
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format, BUFFER_SIZE)
        line.start()
        return line
    }

    private fun startPlayback(filePath: String, seekMs: Long) {
        val pcmFormat = AudioFormat(
            SAMPLE_RATE.toFloat(), SAMPLE_SIZE_BITS, CHANNELS, true, false
        )
        lastReportedMs = seekMs
        backwardsCount = 0

        try {
            val input = startDecoder(filePath, seekMs) ?: return

            val line = openAudioLine(pcmFormat)
            sourceLine = line

            playbackThread = Thread({
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (!isStopped) {
                        if (isPaused) {
                            Thread.sleep(50)
                            continue
                        }
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        line.write(buffer, 0, bytesRead)
                    }
                    if (!isStopped) {
                        line.drain()
                        observer?.onComplete()
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    Logger.e(e, tag = TAG) { "Playback error" }
                }
            }, "JvmAudioPlayback").apply {
                isDaemon = true
                start()
            }

            progressThread = Thread({
                try {
                    while (!isStopped && playbackThread?.isAlive == true) {
                        if (!isPaused) {
                            // The line advances in output time, so atempo-stretched media moves
                            // `speed` times faster than the bytes it has played.
                            val playedMs = (line.microsecondPosition / 1000.0 * speed).toLong()
                            observer?.onProgressUpdate(
                                monotonicPositionMs(seekOffsetMs + playedMs),
                                totalDurationMs
                            )
                        }
                        Thread.sleep(PROGRESS_INTERVAL_MS)
                    }
                } catch (_: InterruptedException) {
                }
            }, "JvmAudioProgress").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Logger.e(e, tag = TAG) { "Failed to start audio playback" }
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        isStopped = true
        process?.destroy()
        process = null
        playbackThread?.interrupt()
        playbackThread = null
        progressThread?.interrupt()
        progressThread = null
        try {
            sourceLine?.stop()
            sourceLine?.close()
        } catch (_: Exception) {
        }
        sourceLine = null
    }

    private fun drainStderr(proc: Process) {
        Thread({
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    Logger.w(tag = TAG) { "ffmpeg: $line" }
                }
            } catch (_: Exception) {
            }
        }, "JvmAudioStderr").apply {
            isDaemon = true
            start()
        }
    }

    // `line.microsecondPosition` restarts at zero across a pause or a seek, so a single
    // backwards sample is jitter; only a sustained run of them is a real rewind.
    internal fun monotonicPositionMs(rawMs: Long): Long {
        val capped = if (totalDurationMs > 0) rawMs.coerceAtMost(totalDurationMs) else rawMs
        if (capped >= lastReportedMs) {
            backwardsCount = 0
            lastReportedMs = capped
            return capped
        }
        backwardsCount++
        if (backwardsCount > BACKWARDS_TOLERANCE) {
            backwardsCount = 0
            lastReportedMs = capped
            return capped
        }
        return lastReportedMs
    }

    protected open fun probeDurationMs(filePath: String): Long {
        if (!FFmpegBinaryManager.isAvailable()) {
            Logger.w(tag = TAG) { "FFmpeg not available, falling back to file-size estimate" }
            return estimateDurationFromFileSize(filePath)
        }
        return try {
            val proc = ProcessBuilder(
                FFmpegBinaryManager.ffprobePath(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                filePath
            ).redirectErrorStream(true).start()

            val output = proc.inputStream.bufferedReader().readText().trim()
            val completed = proc.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                proc.destroy()
                Logger.w(tag = TAG) { "ffprobe timed out" }
            }
            output.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0
        } catch (e: Exception) {
            Logger.e(e, tag = TAG) { "ffprobe failed" }
            0
        }
    }

    companion object {
        private const val TAG = "JvmAudioPlayer"
        internal const val SAMPLE_RATE = 44100
        internal const val SAMPLE_SIZE_BITS = 16
        internal const val CHANNELS = 2
        internal const val BUFFER_SIZE = 8192
        internal const val PROGRESS_INTERVAL_MS = 80L
        internal const val BACKWARDS_TOLERANCE = 3

        internal fun estimateDurationFromFileSize(filePath: String): Long {
            val sizeBytes = File(filePath).length()
            val bytesPerSecond = SAMPLE_RATE * CHANNELS * (SAMPLE_SIZE_BITS / 8)
            return (sizeBytes * 1000 / bytesPerSecond).coerceAtLeast(1)
        }
    }
}
