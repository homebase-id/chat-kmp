package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.homebase.chat.widget.VideoDebugState

/**
 * Platform-specific video player component.
 *
 */
@Composable
expect fun VideoPlayer(
    videoUrl: String?,
    modifier: Modifier = Modifier,
    onDebugUpdate: (VideoDebugState) -> Unit
)
