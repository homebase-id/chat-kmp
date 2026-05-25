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
)
