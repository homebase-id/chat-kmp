package id.homebase.core.audio

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

class IOSAudioRecorder: AudioRecorder {
    private var recorder: AVAudioRecorder? = null

    override fun getAudioFileExtension(): String {
        return "m4a"
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun startRecording(fileName: String) {
        Logger.d { "Starting recording to $fileName" }
        val url = NSURL.fileURLWithPath(fileName)

        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to NSNumber(long = kAudioFormatMPEG4AAC.toLong()), // Use .toLong() for enum constants
            AVSampleRateKey to NSNumber(44100.0),
            AVNumberOfChannelsKey to NSNumber(2)
        )


        // Configure audio session first
        val audioSession = AVAudioSession.sharedInstance()
        memScoped {
            val sessionError = alloc<ObjCObjectVar<NSError?>>()
            audioSession.setCategory(AVAudioSessionCategoryRecord, sessionError.ptr)
            sessionError.value?.let { err ->
                Logger.e { "Failed to set audio session category: ${err.localizedDescription}" }
                return
            }

            audioSession.setActive(true, sessionError.ptr)
            sessionError.value?.let { err ->
                Logger.e { "Failed to activate audio session: ${err.localizedDescription}" }
                return
            }

            val error = alloc<ObjCObjectVar<NSError?>>()
            recorder = AVAudioRecorder(url, settings, error.ptr)

            error.value?.let { err ->
                Logger.e { "Failed to create audio recorder: ${err.localizedDescription}" }
                return
            }

            recorder?.record()
        }
    }

    override fun stopRecording(): String? {
        recorder?.stop()
        return recorder?.url?.path
    }
}