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
)
