package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun LocalVideoPlayerSurface(
    filePath: String,
    modifier: Modifier = Modifier,
    onFirstFrameRendered: () -> Unit = {},
)
