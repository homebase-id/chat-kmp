package id.homebase.chat.widget.video

import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.exoplayer.ExoPlayer
import co.touchlab.kermit.Logger

/**
 * Tiny pool of warm [ExoPlayer] instances shared by the moments inline-video
 * tile (and any other caller of `VideoPlayerSurface.android.kt`).
 *
 * Why: building a fresh `ExoPlayer.Builder(context).build()` on every play tap
 * costs ~100–200ms of object/codec-pipeline setup on the main thread. Signal's
 * `SimpleExoPlayerPool` avoids that cost by leasing pre-built players and
 * resetting their state between uses. The same trick is the biggest win
 * available to us — we only play one inline tile at a time, but every swipe to
 * a new tile re-pays the cost without a pool.
 *
 * Scope: this pool is **only** used by the moments inline-video path
 * (`VideoPlayerSurface.android.kt`). Other surfaces — TrimmableVideoPlayerSurface,
 * LocalVideoPlayerSurface — still build their own players, because their
 * lifecycle is bound to dedicated screens.
 *
 * Sizing: cap at [maxIdle] (default 2). One active + one warm is plenty for
 * our tap-to-play UX. We don't replicate Signal's MediaCodec-derived cap
 * because we never run more than one inline player concurrently.
 *
 * Thread-safety: all calls must happen on the main thread (Compose UI thread).
 * This matches ExoPlayer's own threading contract — it crashes if you call
 * most of its API off-main.
 */
class ExoPlayerPool(
    private val appContext: Context,
    private val maxIdle: Int = 2,
) {
    private val idle: ArrayDeque<ExoPlayer> = ArrayDeque()

    @MainThread
    fun acquire(): ExoPlayer {
        val recycled = idle.removeLastOrNull()
        if (recycled != null) {
            Logger.d(tag = "ExoPlayerPool") { "acquire: reused (idle was ${idle.size + 1})" }
            return recycled.also { resetForNextUse(it) }
        }
        Logger.d(tag = "ExoPlayerPool") { "acquire: built new (idle is empty)" }
        return ExoPlayer.Builder(appContext).build()
    }

    /**
     * Return a player to the pool. The caller MUST have detached the player
     * from any `PlayerView` (`playerView.player = null`) first, otherwise the
     * recycled player will keep painting to a stale surface.
     *
     * Players beyond [maxIdle] are released outright.
     */
    @MainThread
    fun release(player: ExoPlayer) {
        resetForNextUse(player)
        if (idle.size < maxIdle) {
            idle.addLast(player)
            Logger.d(tag = "ExoPlayerPool") { "release: pooled (idle=${idle.size})" }
        } else {
            player.release()
            Logger.d(tag = "ExoPlayerPool") { "release: discarded (pool full at $maxIdle)" }
        }
    }

    @MainThread
    private fun resetForNextUse(player: ExoPlayer) {
        // Clear media + listeners so the next caller gets a blank slate. We
        // don't call `stop()` because `clearMediaItems()` already drops the
        // current source and transitions to IDLE.
        player.clearMediaItems()
        player.playWhenReady = false
    }
}
