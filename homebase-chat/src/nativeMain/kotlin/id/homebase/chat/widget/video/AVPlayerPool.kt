@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.chat.widget.video

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.setMuted
import platform.AVFoundation.setVolume

/**
 * Tiny pool of warm [AVPlayer] instances, mirroring the Android-side
 * [ExoPlayerPool]. Only consulted when the call site opts in via
 * `useInlineOptimizations = true` on [VideoPlayerSurface] — today that is
 * just the moments inline tile, while the chat / moments-detail full-screen
 * player keeps building a fresh `AVPlayer` per play so the dark-launch is
 * tightly scoped.
 *
 * Why pool at all on iOS:
 *   `AVPlayer()` itself is cheap, but each instance brings up its KVO
 *   machinery, notification observers, and a frame renderer. Releasing one
 *   and building another on every play tap measurably pauses the UI thread
 *   when the user scrubs through a feed. Reusing one via
 *   `replaceCurrentItemWithPlayerItem` skips that setup.
 *
 * Why not match Android's pool size of 2:
 *   We do match it. One active + one warm is plenty given that only a single
 *   inline tile plays at a time. Signal iOS doesn't pool at all (their
 *   `UICollectionView` recycling does the equivalent job) — we sit between
 *   "always cold" and "Android-style pool" because our Compose composition
 *   model has neither of their advantages.
 *
 * Threading:
 *   All calls must happen on the main thread. AVFoundation tolerates
 *   off-main reads but the asynchronous KVO/notification routing is
 *   main-queue based; mixing threads here is the fast path to flaky
 *   playback. The callers in `VideoPlayerSurface.native.kt` are all inside
 *   `Dispatchers.Main` blocks.
 */
class AVPlayerPool(
    private val maxIdle: Int = 2,
) {
    private val idle: ArrayDeque<AVPlayer> = ArrayDeque()

    fun acquire(): AVPlayer {
        val recycled = idle.removeLastOrNull()
        if (recycled != null) {
            Logger.d(tag = "AVPlayerPool") { "acquire: reused (idle was ${idle.size + 1})" }
            resetForNextUse(recycled)
            return recycled
        }
        Logger.d(tag = "AVPlayerPool") { "acquire: built new (idle is empty)" }
        return AVPlayer()
    }

    /**
     * Return a player to the pool. The caller MUST have detached every
     * observer (KVO, NSNotificationCenter, periodic time observer) attached
     * during the play session before calling this — otherwise the recycled
     * player will keep firing callbacks for a torn-down session. Detaching
     * the player from any hosting `AVPlayerLayer` /
     * `AVPlayerViewController.player` is also the caller's job.
     *
     * Players beyond [maxIdle] are simply dropped; ARC handles the actual
     * deallocation once the last reference is gone.
     */
    fun release(player: AVPlayer) {
        resetForNextUse(player)
        if (idle.size < maxIdle) {
            idle.addLast(player)
            Logger.d(tag = "AVPlayerPool") { "release: pooled (idle=${idle.size})" }
        } else {
            // Fall through — no explicit release call on AVPlayer; the local
            // reference falling out of scope is enough.
            Logger.d(tag = "AVPlayerPool") { "release: discarded (pool full at $maxIdle)" }
        }
    }

    private fun resetForNextUse(player: AVPlayer) {
        // Stop playback, drop the current item, and restore volume/mute
        // defaults so the next caller gets a blank slate.
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        player.setMuted(false)
        player.setVolume(1f)
    }
}
