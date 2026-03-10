package id.homebase.core.audio

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.Foundation.NSURL
import platform.darwin.NSObject

class IOSAudioPlayer : AudioPlayer {
    private var player: AVAudioPlayer? = null
    private var observer: AudioPlaybackObserver? = null
    private val delegate = AudioPlayerDelegate()

    @OptIn(ExperimentalForeignApi::class)
    override fun play(filePath: String) {
        val url = NSURL.fileURLWithPath(filePath)
        player = AVAudioPlayer(url, null).apply {
            this.delegate = this@IOSAudioPlayer.delegate
            this@IOSAudioPlayer.delegate.observer = this@IOSAudioPlayer.observer
            play()
        }
    }

    override fun resume() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
        player?.stop()
    }

    override fun release() {
        player = null
    }

    override fun setPlaybackObserver(observer: AudioPlaybackObserver) {
        this.observer = observer
    }

    private class AudioPlayerDelegate : NSObject(), AVAudioPlayerDelegateProtocol {
        var observer: AudioPlaybackObserver? = null

        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            observer?.onComplete()
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: platform.Foundation.NSError?) {
            // Handle decode errors if needed
        }
    }
}