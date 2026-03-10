package id.homebase.core.audio

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidAudioPlayer: AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var observer: AudioPlaybackObserver? = null
    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun play(filePath: String) {
        release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(filePath)
            setOnCompletionListener { observer?.onComplete() }
            prepare()
            start()
        }
        startPositionPolling()
    }

    override fun resume() {
        mediaPlayer?.start()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun stop() {
        positionJob?.cancel()
        mediaPlayer?.stop()
    }

    override fun release() {
        positionJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun setPlaybackObserver(observer: AudioPlaybackObserver) {
        this.observer = observer
    }

    private fun startPositionPolling() {
        positionJob = scope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                val position = mediaPlayer?.currentPosition ?: 0
                val duration = mediaPlayer?.duration ?: 0
                observer?.onProgressUpdate(position * 1000, duration * 1000)
                delay(500)
            }
        }
    }
}