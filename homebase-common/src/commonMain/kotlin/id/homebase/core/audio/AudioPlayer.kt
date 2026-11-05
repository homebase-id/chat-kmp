package id.homebase.core.audio

/**
 * Plays one local audio file at a time. Calls return immediately and are safe from the main
 * thread; decoding happens elsewhere. [jumpTo], [resume], [pause] and [stop] do nothing when no
 * file is loaded.
 *
 * Observer callbacks come on a background thread (the main thread on web), so hop to the main
 * thread before touching UI.
 */
interface AudioPlayer {
    /**
     * Stops whatever is playing and starts [filePath] from the beginning at the speed last set.
     * A file that is missing or cannot be decoded does not throw: the player is left idle and
     * [AudioPlaybackObserver.onComplete] fires once, so a UI waiting on it does not hang. On web
     * [filePath] is a path in the in-memory file system, not a URL.
     */
    fun play(filePath: String)

    /** Seeks to [positionMs], clamped to the file's length. Playback state is unchanged. */
    fun jumpTo(positionMs: Long)

    /** Continues after [pause]. Only valid after [pause]; after [stop] call [play] again. */
    fun resume()

    fun pause()

    /** Ends playback and drops the position; [AudioPlaybackObserver.onComplete] does not fire. */
    fun stop()

    /**
     * Frees the underlying player. Call [play] to use the instance again; the observer may have
     * been cleared, so set it again first.
     */
    fun release()

    /** Applies [speed] clamped to [MIN_PLAYBACK_SPEED]..[MAX_PLAYBACK_SPEED], and to later [play] calls. */
    fun setSpeed(speed: Float)

    /** Replaces the observer. There is one; setting a second drops the first. */
    fun setPlaybackObserver(observer: AudioPlaybackObserver)
}

interface AudioPlaybackObserver {
    /** The file played to its end, or could not be played. Not called after [AudioPlayer.stop] or [AudioPlayer.release]. */
    fun onComplete()

    /** About every 80 ms while playing (web: as the browser reports it). [durationMs] is 0 when unknown. */
    fun onProgressUpdate(positionMs: Long, durationMs: Long)
}

const val MIN_PLAYBACK_SPEED = 0.5f
const val MAX_PLAYBACK_SPEED = 2.0f

fun Float.coerceToPlaybackSpeed(): Float = coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)

expect fun getAudioPlayer(): AudioPlayer
