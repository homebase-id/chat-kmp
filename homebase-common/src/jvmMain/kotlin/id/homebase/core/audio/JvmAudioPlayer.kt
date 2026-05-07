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
    internal var totalDurationSeconds: Int = 0
        private set

    @Volatile
    internal var seekOffsetSeconds: Int = 0
        private set

    @Volatile
    private var isPaused = false

    @Volatile
    private var isStopped = true

    override fun play(filePath: String) {
        stopPlayback()
        currentFilePath = filePath
        seekOffsetSeconds = 0
        isStopped = false
        isPaused = false
        totalDurationSeconds = probeDurationSeconds(filePath)
        startPlayback(filePath, seekSeconds = 0)
    }

    override fun pause() {
        isPaused = true
        sourceLine?.stop()
    }

    override fun resume() {
        sourceLine?.start()
        isPaused = false
    }

    override fun jump(seconds: Int) {
        val path = currentFilePath ?: return
        val clamped = seconds.coerceIn(0, totalDurationSeconds)
        stopPlayback()
        seekOffsetSeconds = clamped
        isStopped = false
        isPaused = false
        startPlayback(path, seekSeconds = clamped)
    }

    override fun stop() {
        isStopped = true
        stopPlayback()
        seekOffsetSeconds = 0
    }

    override fun release() {
        stop()
        observer = null
        currentFilePath = null
    }

    override fun setPlaybackObserver(observer: AudioPlaybackObserver) {
        this.observer = observer
    }

    internal fun buildFfmpegCommand(filePath: String, seekSeconds: Int): List<String> = buildList {
        add(FFmpegBinaryManager.ffmpegPath())
        add("-v"); add("error")
        if (seekSeconds > 0) {
            add("-ss"); add(seekSeconds.toString())
        }
        add("-i"); add(filePath)
        add("-f"); add("s16le")
        add("-acodec"); add("pcm_s16le")
        add("-ar"); add(SAMPLE_RATE.toString())
        add("-ac"); add(CHANNELS.toString())
        add("pipe:1")
    }

    protected open fun startDecoder(filePath: String, seekSeconds: Int): InputStream? {
        if (!FFmpegBinaryManager.isAvailable()) {
            Logger.e(tag = TAG) { "FFmpeg binaries not available — cannot decode audio" }
            return null
        }
        val command = buildFfmpegCommand(filePath, seekSeconds)
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

    private fun startPlayback(filePath: String, seekSeconds: Int) {
        val pcmFormat = AudioFormat(
            SAMPLE_RATE.toFloat(), SAMPLE_SIZE_BITS, CHANNELS, true, false
        )

        try {
            val input = startDecoder(filePath, seekSeconds) ?: return

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
                            val linePositionSeconds =
                                (line.microsecondPosition / 1_000_000).toInt()
                            val currentSeconds = seekOffsetSeconds + linePositionSeconds
                            observer?.onProgressUpdate(
                                currentSeconds.coerceAtMost(totalDurationSeconds),
                                totalDurationSeconds
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

    protected open fun probeDurationSeconds(filePath: String): Int {
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
            output.toDoubleOrNull()?.toInt() ?: 0
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
        internal const val PROGRESS_INTERVAL_MS = 500L

        internal fun estimateDurationFromFileSize(filePath: String): Int {
            val sizeBytes = File(filePath).length()
            val bytesPerSecond = SAMPLE_RATE * CHANNELS * (SAMPLE_SIZE_BITS / 8)
            return (sizeBytes / bytesPerSecond).toInt().coerceAtLeast(1)
        }
    }
}
