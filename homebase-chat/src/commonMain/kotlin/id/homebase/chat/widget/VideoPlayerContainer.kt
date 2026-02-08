package id.homebase.chat.widget
//
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.CircularProgressIndicator
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.Text
//import androidx.compose.runtime.*
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import co.touchlab.kermit.Logger
//import id.homebase.api.video.player.VideoPlaybackPreparationResult
//import id.homebase.api.video.player.prepareVideoContentForPlayback
//import id.homebase.api.video.player.unprepareVideoContent
//import id.homebase.homebasekmppoc.prototype.ui.video.VideoPlayer
//
//@Composable
//fun VideoPlayerContainer(
//    videoServer: LocalVideoServer,
//    videoPayload: PayloadWrapper
//) {
//    // 1. Manage State
//    var viewState by remember { mutableStateOf<VideoPlaybackPreparationResult?>(null) }
//
//    // 2. Trigger Preparation (Logic moved to helper function)
//    LaunchedEffect(videoPayload) {
//        viewState = prepareVideoContentForPlayback(appOrOwner, videoServer, videoPayload)
//    }
//
//    // 3. Cleanup Logic
//    // We derive the ID purely for the effect key
//    val activeContentId = (viewState as? VideoPlaybackPreparationResult.Success)?.contentId
//
//    DisposableEffect(activeContentId) {
//        // CAPTURE: Store the ID associated with THIS effect cycle locally.
//        val idToRelease = activeContentId
//
//        onDispose {
//            if (idToRelease != null) {
//                Logger.d("VideoPlayer") { "Unregistering content: $idToRelease" }
//                unprepareVideoContent(idToRelease, videoServer)
//            }
//        }
//    }
//
//    // 4. UI Rendering
//    Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
//        when (val state = viewState) {
//            null -> {
//                CircularProgressIndicator(Modifier.align(Alignment.Center))
//            }
//            is VideoPlaybackPreparationResult.Error -> {
//                Text(
//                    text = "Unable to load video: ${state.message}",
//                    color = MaterialTheme.colorScheme.error,
//                    modifier = Modifier.align(Alignment.Center)
//                )
//            }
//            is VideoPlaybackPreparationResult.Success -> {
//                // If this is HLS, state.url is the Manifest. If MP4, it's the direct link.
//                // We delegate to the HLS player (which handles proxied files)
//                // or the standard player depending on your preference.
//                // Since your Prepare logic standardizes the URL, you might only need one player.
//                VideoPlayer(
//                    videoUrl = state.url,
//                    modifier = Modifier.matchParentSize()
//                )
//            }
//        }
//    }
//}
