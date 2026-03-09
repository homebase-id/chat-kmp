package id.homebase.core.audio

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSURL

class IOSAudioRecorder: AudioRecorder {
    private var recorder: AVAudioRecorder? = null

    @OptIn(ExperimentalForeignApi::class)
    override fun startRecording(fileName: String) {
        val url = NSURL.fileURLWithPath(fileName)
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC,
            AVSampleRateKey to 44100.0,
            AVNumberOfChannelsKey to 2
        )
        recorder = AVAudioRecorder(url, settings, null)
        recorder?.record()
    }

    override fun stopRecording(): String? {
        recorder?.stop()
        return recorder?.url?.path
    }
}