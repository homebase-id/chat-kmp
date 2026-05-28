package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.homebase.chat.conversationlist.FullScreenOverlay

@Composable
expect fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier = Modifier,
    onProgress: (Float) -> Unit = {},
    muted: Boolean = false,
    /**
     * Whether the platform's built-in playback controls (tap-to-show
     * play/pause/scrubber) should be active. The moments-feed carousel sets
     * this to `false` so the native view doesn't swallow horizontal drag
     * events the HorizontalPager needs for paging. Defaults to `true` to
     * preserve existing full-screen/single-video behaviour.
     */
    useNativeControls: Boolean = true,
    /**
     * Opt into the inline-tile playback optimizations: warm player pool,
     * audio-renderer-off-when-muted, and first-frame-paint gating. Off by
     * default so existing callers (chat full-screen, moments-detail
     * full-screen) keep their current behaviour exactly. Set to `true` from
     * the moments inline tile to dark-launch the optimizations there first.
     *
     * Each actual decides which subset of these tricks applies on its
     * platform — Android already pools its ExoPlayers unconditionally so the
     * flag is a no-op there; the iOS actual is the primary consumer.
     */
    useInlineOptimizations: Boolean = false,
    /**
     * Initial playback position the underlying player should seek to after
     * `prepare()`. Used for the feed→detail (and detail→feed) handoff so the
     * second screen picks up where the first left off. Pass `0L` (the
     * default) for "start at the beginning."
     */
    startPositionMs: Long = 0L,
    /**
     * Invoked periodically with the current playback position while the
     * video is playing. Callers are expected to throttle their own writes
     * downstream — the actual fires no more frequently than ~once per
     * 500 ms. Used by the inline tile to push the latest position into
     * [id.homebase.core.moments.services.MomentsVideoSession] so the
     * handoff is fresh whenever the user navigates away.
     */
    onPositionUpdate: (Long) -> Unit = {},
    /**
     * Fires once per mount, when the underlying player has pushed its first
     * decoded frame to the rendering surface. Callers use this to drop a
     * thumbnail overlay drawn over the player — the moments inline tile keeps
     * the thumbnail visible until this fires so the user never sees the
     * empty/garbage texture state during decoder warm-up.
     *
     * Best-effort on platforms without an exact first-frame callback (iOS,
     * web): may fire on "ready to play" instead, which is a few frames early.
     */
    onFirstFrame: () -> Unit = {},
)
