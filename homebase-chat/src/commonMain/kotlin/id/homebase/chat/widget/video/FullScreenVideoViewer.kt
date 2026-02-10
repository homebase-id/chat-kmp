package id.homebase.chat.widget.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import id.homebase.chat.FullScreenMessageData
import id.homebase.chat.widget.VideoDebugState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenVideoViewer(
    data: FullScreenMessageData,
    onDismiss: () -> Unit
) {
    val videoPlaybackPreparer: VideoPlaybackPreparer = org.koin.compose.koinInject()

    var showUI by remember { mutableStateOf(true) }
    var videoState by remember {
        mutableStateOf<VideoPlaybackPreparationResult>(
            VideoPlaybackPreparationResult.Loading
        )
    }
    var debugState by remember { mutableStateOf(VideoDebugState()) }
    var debugUrl: String = "empty"

    val payloadKey = data.selectedPayloadKey

    /* ---- prepare video ---- */
    LaunchedEffect(payloadKey) {
        videoState = VideoPlaybackPreparationResult.Loading

        videoState = videoPlaybackPreparer.prepareVideoContentForPlayback(
            data.driveId,
            data.fileId,
            data.selectedPayloadKey,
            data.keyHeader
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {

        /* ───────── TOP BAR ROW ───────── */
        AnimatedVisibility(
            visible = showUI,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            TopAppBar(
                title = { Text("Video") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Text("←")
                    }
                }
            )
        }

        /* ───────── VIDEO ROW ───────── */
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures { showUI = !showUI }
                },
            contentAlignment = Alignment.Center
        ) {
            when (val state = videoState) {
                VideoPlaybackPreparationResult.Loading -> {
                    CircularProgressIndicator()
                }

                is VideoPlaybackPreparationResult.Error -> {
                    Text(
                        text = "Video error: ${state.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                is VideoPlaybackPreparationResult.Success -> {
                    debugUrl = state.url
                    VideoPlayer(
                        videoUrl = state.url,
                        modifier = Modifier.fillMaxSize(),
                        onDebugUpdate = { debugState = it }
                    )
                }
            }
        }

        /* ───────── DEBUG / STATUS ROW ───────── */
        AnimatedVisibility(
            visible = showUI,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(12.dp)
            ) {

                OutlinedTextField(
                    value = "URL: $debugUrl",
                    onValueChange = {},
                    readOnly = true
                )

                Text("Status: ${debugState.status}")
                if (debugState.buffered.isNotEmpty()) {
                    Text("Buffered: ${debugState.buffered}")
                }
                debugState.error?.let {
                    Text("Error: $it", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
