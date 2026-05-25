package id.homebase.core.audio

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.darwin.NSObject

class IOSAudioPlayer : AudioPlayer {
    private var player: AVAudioPlayer? = null
    private val delegate = AudioPlayerDelegate()

    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun play(filePath: String) {
        // Configure audio session
        val audioSession = AVAudioSession.sharedInstance()
        memScoped {
            val sessionError = alloc<ObjCObjectVar<NSError?>>()
            audioSession.setCategory(AVAudioSessionCategoryPlayback, sessionError.ptr)
            sessionError.value?.let { err ->
                Logger.e { "Failed to set audio session category: ${err.localizedDescription}" }
                return
            }

            audioSession.setActive(true, sessionError.ptr)
            sessionError.value?.let { err ->
                Logger.e { "Failed to activate audio session: ${err.localizedDescription}" }
                return
            }
        }

        // Create and start player — AVAudioPlayer's ObjC init returns nil for
        // unplayable files, and K/N's interop bridge throws NPE for nil failable
        // inits, so we must catch at the call site.
        val url = NSURL.fileURLWithPath(filePath)
        val newPlayer: AVAudioPlayer? = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            try {
                val p = AVAudioPlayer(url, error.ptr)
                error.value?.let { err ->
                    Logger.e { "Failed to create audio player: ${err.localizedDescription}" }
                    return@memScoped null
                }
                p
            } catch (_: Exception) {
                val errMsg = error.value?.localizedDescription ?: "unknown"
                Logger.e { "AVAudioPlayer init threw for: $filePath — $errMsg" }
                null
            }
        }

        if (newPlayer == null) {
            val fm = NSFileManager.defaultManager
            val exists = fm.fileExistsAtPath(filePath)
            val attrs = fm.attributesOfItemAtPath(filePath, null)
            val size = attrs?.get("NSFileSize") ?: "unknown"
            Logger.e { "Audio player init failed — path=$filePath exists=$exists size=$size" }
            delegate.observer?.onComplete()
            return
        }

        newPlayer.delegate = delegate
        newPlayer.prepareToPlay()
        newPlayer.play()
        player = newPlayer
        startPositionPolling()
    }

    override fun jump(seconds: Int) {
        player?.currentTime = seconds.toDouble()
    }

    override fun resume() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
        positionJob?.cancel()
        player?.stop()
    }

    override fun release() {
        positionJob?.cancel()
        player?.stop()
        player = null
    }

    override fun setPlaybackObserver(observer: AudioPlaybackObserver) {
        delegate.observer = observer
    }

    private fun startPositionPolling() {
        positionJob = scope.launch {
            while (isActive) {
                val position = player?.currentTime?.toInt() ?: 0
                val duration = player?.duration?.toInt() ?: 0
                delegate.observer?.onProgressUpdate(position, duration)
                delay(500)
            }
        }
    }

    private class AudioPlayerDelegate : NSObject(), AVAudioPlayerDelegateProtocol {
        var observer: AudioPlaybackObserver? = null

        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            observer?.onComplete()
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            // Handle decode errors if needed
            Logger.e { "AudioPlayer decode error: ${error?.localizedDescription}" }
        }
    }
}