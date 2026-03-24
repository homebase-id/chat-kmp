package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun LocalVideoPlayerSurface(
    filePath: String,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit,
) {
    VlcjPlayer(videoPath = filePath, modifier = modifier, onFirstFrameRendered = onFirstFrameRendered)
}
