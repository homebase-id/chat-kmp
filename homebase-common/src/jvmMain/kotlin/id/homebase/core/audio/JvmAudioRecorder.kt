package id.homebase.core.audio

import co.touchlab.kermit.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

class JvmAudioRecorder : AudioRecorder {
    private var line: TargetDataLine? = null
    private var audioFileName: String? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false

    override fun getAudioFileExtension(): String {
        return "wav"
    }

    override fun startRecording(fileName: String) {
        audioFileName = fileName
        Logger.d(tag = TAG) { "Starting recording to: $fileName" }

        try {
            // Find a suitable audio format
            val format = findSupportedFormat()
                ?: throw IllegalStateException("No supported audio input format found")
            Logger.d(tag = TAG) { "Using format: ${format.sampleRate}Hz, ${format.channels} channel(s), ${format.sampleSizeInBits} bit" }

            // Find the best mixer that supports this format
            val mixer = findBestMixerForRecording(format)
                ?: throw IllegalStateException("No audio input device found for recording")
            Logger.d(tag = TAG) { "Using mixer: ${mixer.mixerInfo.name}" }

            val info = DataLine.Info(TargetDataLine::class.java, format)

            // Get line from the specific mixer
            line = mixer.getLine(info) as TargetDataLine
            Logger.d(tag = TAG) { "Got TargetDataLine from mixer" }

            // Open with explicit buffer size (1 second of audio)
            val bufferSize = (format.sampleRate * format.frameSize).toInt()
            line?.open(format, bufferSize)
            Logger.d(tag = TAG) { "Opened line with buffer size: $bufferSize bytes" }

            line?.start()
            Logger.d(tag = TAG) { "Started line - isActive: ${line?.isActive}, isOpen: ${line?.isOpen}" }

            isRecording = true

            // Run recording in background thread with manual read loop
            recordingThread = Thread {
                try {
                    Logger.d(tag = TAG) { "Recording thread started" }

                    val buffer = ByteArray(bufferSize / 5) // Read in smaller chunks
                    val outputStream = ByteArrayOutputStream()
                    var totalBytesRead = 0
                    var emptyReads = 0

                    while (isRecording) {
                        val bytesRead = line?.read(buffer, 0, buffer.size) ?: 0

                        if (bytesRead > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            // Log every second of audio captured
                            if (totalBytesRead % bufferSize < buffer.size) {
                                Logger.d(tag = TAG) { "Captured ${totalBytesRead / format.frameSize / format.sampleRate}s of audio (${totalBytesRead} bytes)" }
                            }
                            emptyReads = 0
                        } else {
                            emptyReads++
                            if (emptyReads % 100 == 0) {
                                Logger.w(tag = TAG) { "No data read from microphone after $emptyReads attempts" }
                            }
                        }

                        // Small sleep to prevent busy loop
                        Thread.sleep(10)
                    }

                    Logger.d(tag = TAG) { "Recording stopped. Total bytes captured: $totalBytesRead" }

                    // Write to file
                    if (totalBytesRead > 0) {
                        val audioData = outputStream.toByteArray()
                        val audioInputStream = AudioInputStream(
                            audioData.inputStream(),
                            format,
                            (audioData.size / format.frameSize).toLong()
                        )
                        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, File(fileName))
                        Logger.d(tag = TAG) { "Successfully wrote ${audioData.size} bytes to $fileName" }
                    } else {
                        Logger.e(tag = TAG) { "No audio data captured! File will be empty." }
                    }
                } catch (e: Exception) {
                    Logger.e(
                        throwable = e,
                        tag = TAG
                    ) { "Error in recording thread" }
                }
            }.apply {
                name = "AudioRecorderThread"
                start()
            }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Failed to start recording" }
            throw e
        }
    }

    override fun stopRecording(): String? {
        Logger.d(tag = TAG) { "Stopping recording" }

        isRecording = false

        try {
            // Wait for recording thread to finish
            recordingThread?.join(5000) // Wait max 5 seconds

            line?.stop()
            Logger.d(tag = TAG) { "Line stopped" }

            line?.drain()
            Logger.d(tag = TAG) { "Line drained" }

            line?.close()
            Logger.d(tag = TAG) { "Line closed" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Error stopping recording" }
        }

        return audioFileName
    }

    private fun findSupportedFormat(): AudioFormat? {
        // Preferred formats in order of preference
        val formats = listOf(
            AudioFormat(44100.0f, 16, 1, true, false), // mono, 44.1kHz
            AudioFormat(48000.0f, 16, 1, true, false), // mono, 48kHz
            AudioFormat(16000.0f, 16, 1, true, false), // mono, 16kHz
            AudioFormat(44100.0f, 16, 2, true, false), // stereo, 44.1kHz
            AudioFormat(8000.0f, 16, 1, true, false),  // mono, 8kHz (lower quality fallback)
        )

        for (format in formats) {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (AudioSystem.isLineSupported(info)) {
                Logger.d(tag = TAG) { "Found supported format: ${format.sampleRate}Hz, ${format.channels}ch" }
                return format
            }
        }

        Logger.e(tag = TAG) { "No supported format found!" }
        return null
    }

    /**
     * Finds the best mixer for recording audio.
     * Prioritizes mixers with "microphone" or "input" in their name.
     */
    private fun findBestMixerForRecording(format: AudioFormat): Mixer? {
        val mixerInfos = AudioSystem.getMixerInfo()
        val info = DataLine.Info(TargetDataLine::class.java, format)

        Logger.d(tag = TAG) { "Scanning ${mixerInfos.size} audio mixers..." }


        val candidates = mutableListOf<Pair<Mixer.Info, Int>>()

        for (mixerInfo in mixerInfos) {
            val mixer = AudioSystem.getMixer(mixerInfo)

            // Check if this mixer supports TargetDataLine (input)
            val supportsInput = mixer.isLineSupported(info)
            Logger.d(tag = TAG) { "  - ${mixerInfo.name} | ${mixerInfo.description} (supports input: $supportsInput)" }

            if (supportsInput) {
                // Score mixers to prefer likely microphone devices
                var score: Int
                val nameLower = mixerInfo.name.lowercase()

                score = when {
                    nameLower.contains("microphone") || nameLower.contains("mic") -> 100
                    nameLower.contains("input") || nameLower.contains("capture") -> 50
                    nameLower.contains("chat") -> 40
                    nameLower.contains("built-in") -> 25
                    else -> 10
                }

                candidates.add(mixerInfo to score)
                Logger.d(tag = TAG) { "    -> Candidate with score: $score" }
            }
        }

        // Return the mixer with the highest score
        val bestMixer = candidates.maxByOrNull { it.second }?.first

        if (bestMixer != null) {
            Logger.d(tag = TAG) { "✓ Selected mixer: ${bestMixer.name}" }
            return AudioSystem.getMixer(bestMixer)
        }

        Logger.e(tag = TAG) { "✗ No suitable mixer found for recording" }
        return null
    }
    
    companion object {
        const val TAG = "JvmAudioRecorder"
    }
}