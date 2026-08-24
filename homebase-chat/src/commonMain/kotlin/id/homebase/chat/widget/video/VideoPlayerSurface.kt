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
    /**
     * Override the video's resize behaviour when the caller is using native
     * controls. `false` (default) → fit-with-letterbox (Android
     * `RESIZE_MODE_FIT` / iOS `.resizeAspect`): whole frame visible, black
     * bars fill the aspect-ratio mismatch. `true` → crop-to-fill (Android
     * `RESIZE_MODE_ZOOM` / iOS `.resizeAspectFill`): the video fills the
     * surface edge-to-edge; portions that don't match the surface aspect are
     * cropped off the edges.
     *
     * Ignored when [useNativeControls] is false — that branch is the feed
     * carousel, which always crops to its own locked aspect.
     */
    useZoomFill: Boolean = false,
    /**
     * Fires once when the underlying player reaches the end of the clip
     * (Android `STATE_ENDED`, iOS `AVPlayerItemDidPlayToEndTime`, Desktop VLC
     * `finished()`, web `<video> ended`). The moments inline tile uses this to
     * pause-at-last-frame and raise a "Watch again" affordance instead of
     * looping. Best-effort on platforms without an exact callback. No-op by
     * default so existing full-screen callers keep their current behaviour.
     */
    onEnded: () -> Unit = {},
    /**
     * Replay trigger. Each time the caller increments this past its previous
     * value the actual seeks the live player back to 0 and resumes — *in
     * place*, without remounting the surface (so no thumbnail flash, no
     * re-download, and the feed↔detail position handoff isn't disturbed). `0`
     * (the default) means "never replayed yet" and is ignored. Used by the
     * "Watch again" button after [onEnded] has fired.
     */
    replayToken: Int = 0,
    /**
     * Pause the player *in place* without tearing the surface down — the
     * current frame stays on screen and playback resumes from the same
     * position when this flips back to `false`. Used by the moments inline
     * tile's press-and-hold-to-pause gesture (Instagram-style). Defaults to
     * `false` so existing callers, which pause by unmounting the surface, are
     * unaffected.
     */
    paused: Boolean = false,
    /**
     * Fires with a localized, user-facing message when playback fails (decode
     * error, unsupported codec, content-resolution failure). The surface also
     * renders that message centred in its own bounds — but a caller that draws
     * a thumbnail *over* the surface (the moments inline tile keeps its poster
     * up until [onFirstFrame], which a failed playback never reaches) would
     * hide it completely, so the error has to be re-raised above that overlay
     * by the caller (#959).
     *
     * May fire more than once for a single mount if the player reports
     * successive errors; the latest message wins.
     */
    onError: (String) -> Unit = {},
)
