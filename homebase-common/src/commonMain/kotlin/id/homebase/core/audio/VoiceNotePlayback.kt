package id.homebase.core.audio

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class VoiceNotePlaybackState(
    val playingKey: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
)

interface VoiceNotePlayback {
    val state: StateFlow<VoiceNotePlaybackState>

    suspend fun play(key: String, filePath: String)
    fun pause()
    fun resume()
    fun seekTo(fraction: Float)
    fun stop()
    fun setSpeed(speed: Float)
}

class DefaultVoiceNotePlayback(
    private val player: AudioPlayer,
    private val proximityRouter: ProximityAudioRouter,
    private val scope: CoroutineScope,
) : VoiceNotePlayback {

    private val _state = MutableStateFlow(VoiceNotePlaybackState())
    override val state: StateFlow<VoiceNotePlaybackState> = _state.asStateFlow()

    init {
        player.setPlaybackObserver(object : AudioPlaybackObserver {
            override fun onComplete() {
                proximityRouter.stop()
                _state.update { it.copy(positionMs = 0, isPlaying = false) }
            }

            override fun onProgressUpdate(positionMs: Long, durationMs: Long) {
                _state.update { current ->
                    if (current.playingKey == null) {
                        current
                    } else {
                        current.copy(
                            positionMs = positionMs,
                            // 0 means the player couldn't determine a duration (web: a stream muxed
                            // with no duration box) — keep whatever length we already had.
                            durationMs = if (durationMs > 0) durationMs else current.durationMs,
                        )
                    }
                }
            }
        })
    }

    override suspend fun play(key: String, filePath: String) {
        _state.update {
            it.copy(playingKey = key, positionMs = 0, durationMs = 0, isPlaying = true)
        }
        val speed = _state.value.speed
        withContext(Dispatchers.Default) {
            player.stop()
            player.play(filePath)
            player.setSpeed(speed)
        }
        proximityRouter.start()
    }

    override fun pause() {
        if (_state.value.playingKey == null) return
        player.pause()
        proximityRouter.stop()
        _state.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        if (_state.value.playingKey == null) return
        player.resume()
        proximityRouter.start()
        _state.update { it.copy(isPlaying = true) }
    }

    override fun seekTo(fraction: Float) {
        val current = _state.value
        if (current.playingKey == null || current.durationMs <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * current.durationMs).toLong()
        _state.update { it.copy(positionMs = target) }
        // The injected app scope is Dispatchers.Default; both of these restart a decoder.
        scope.launch { player.jumpTo(target) }
    }

    override fun stop() {
        proximityRouter.stop()
        player.stop()
        _state.update {
            it.copy(playingKey = null, positionMs = 0, durationMs = 0, isPlaying = false)
        }
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceToPlaybackSpeed()
        if (clamped == _state.value.speed) return
        _state.update { it.copy(speed = clamped) }
        scope.launch { player.setSpeed(clamped) }
    }
}
