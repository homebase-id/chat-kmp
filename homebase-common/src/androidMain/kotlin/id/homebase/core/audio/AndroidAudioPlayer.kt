package id.homebase.core.audio

import android.media.MediaPlayer
import co.touchlab.kermit.Logger
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
    private var speed = 1f

    override fun play(filePath: String) {
        release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(filePath)
            setOnCompletionListener { observer?.onComplete() }
            prepare()
            start()
        }
        applySpeed()
        startPositionPolling()
    }

    override fun jumpTo(positionMs: Long) {
        mediaPlayer?.let {
            it.seekTo(positionMs.toInt().coerceIn(0, it.duration))
        }
    }

    override fun resume() {
        mediaPlayer?.start()
        applySpeed()
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceToPlaybackSpeed()
        applySpeed()
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

    // MediaPlayer.playbackParams resumes a paused player as a side effect, so only touch it
    // while it is already running.
    private fun applySpeed() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) return
        runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
            .onFailure { Logger.w(it) { "setPlaybackParams($speed) rejected" } }
    }

    private fun startPositionPolling() {
        positionJob = scope.launch {
            while (isActive) {
                val position = mediaPlayer?.currentPosition ?: 0
                val duration = mediaPlayer?.duration ?: 0
                observer?.onProgressUpdate(position.toLong(), duration.toLong())
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 80L
    }
}