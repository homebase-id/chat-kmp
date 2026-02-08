package id.homebase.chat.widget.video


import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import co.touchlab.kermit.Logger
import id.homebase.chat.FullScreenMessageData

/* -----------------------------
   STUB: Local video server
-------------------------------- */

class LocalVideoServer {
    fun startIfNeeded() {
        Logger.d("LocalVideoServer") { "startIfNeeded()" }
    }

    fun registerVideo(payloadKey: String): String {
        Logger.d("LocalVideoServer") { "registerVideo($payloadKey)" }
        return "http://localhost:12345/video/$payloadKey/playlist.m3u8"
    }

    fun unregisterVideo(payloadKey: String) {
        Logger.d("LocalVideoServer") { "unregisterVideo($payloadKey)" }
    }
}

/* -----------------------------
   STUB: Preparation result
-------------------------------- */

sealed class VideoPrepResult {
    object Loading : VideoPrepResult()
    data class Ready(val url: String) : VideoPrepResult()
    data class Error(val message: String) : VideoPrepResult()
}

/* -----------------------------
   STUB: Preparation logic
-------------------------------- */

suspend fun prepareVideo(
    server: LocalVideoServer,
    payloadKey: String
): VideoPrepResult {
    return try {
        server.startIfNeeded()
        val url = server.registerVideo(payloadKey)
        VideoPrepResult.Ready(url)
    } catch (e: Exception) {
        VideoPrepResult.Error(e.message ?: "Unknown error")
    }
}

/* -----------------------------
   SCREEN
-------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenVideoViewer(
    data: FullScreenMessageData,
    onDismiss: () -> Unit
) {
    val videoServer = remember { LocalVideoServer() }

    var showUI by remember { mutableStateOf(true) }
    var videoState by remember { mutableStateOf<VideoPrepResult>(VideoPrepResult.Loading) }

    val payloadKey = data.selectedPayloadKey

    /* ---- prepare video ---- */
    LaunchedEffect(payloadKey) {
        videoState = VideoPrepResult.Loading
        videoState = prepareVideo(videoServer, payloadKey)
    }

    /* ---- cleanup ---- */
    DisposableEffect(payloadKey) {
        onDispose {
            videoServer.unregisterVideo(payloadKey)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {

        /* ---- video content ---- */
        when (val state = videoState) {
            VideoPrepResult.Loading -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            is VideoPrepResult.Error -> {
                Text(
                    text = "Video error: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is VideoPrepResult.Ready -> {
                VideoPlayer(
                    videoUrl = state.url,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                showUI = !showUI
                            }
                        }
                )
            }
        }

        /* ---- simple top bar ---- */
        if (showUI) {
            TopAppBar(
                title = { Text("Video") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Text("←")
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
