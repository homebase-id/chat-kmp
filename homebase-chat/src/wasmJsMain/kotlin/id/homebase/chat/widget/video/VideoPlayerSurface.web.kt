package id.homebase.chat.widget.video

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.homebase.chat.conversationlist.FullScreenOverlay

// Pre-flight stub. A real implementation would use HTMLVideoElement via a
// Compose-html bridge — out of scope for the pre-flight.
@Composable
actual fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier,
    onProgress: (Float) -> Unit,
    muted: Boolean,
) {
    Box(modifier = modifier)
}
