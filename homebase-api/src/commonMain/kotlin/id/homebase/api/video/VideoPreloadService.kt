package id.homebase.api.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Launches [VideoPreloader] downloads on an app-scoped coroutine so they survive the chat list
 * leaving composition. Without this, MediaItem's preload gets cancelled the instant the user
 * taps a video — discarding bytes already in flight and forcing the full-screen surface to
 * restart the download from scratch.
 *
 * Dedup and serialization are handled inside [VideoPreloader] via its per-key mutex, so this
 * class is intentionally a thin fire-and-forget wrapper.
 */
class VideoPreloadService(
    private val videoPreloader: VideoPreloader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Schedule a background preload. Returns immediately. Safe to call repeatedly for the
     *  same (fileId, payloadKey) — duplicate in-flight calls serialize on the preloader's mutex,
     *  with the second hitting the warm disk cache. */
    fun requestPreload(data: VideoPlayerData) {
        scope.launch { videoPreloader.preload(data) }
    }
}
