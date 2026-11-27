package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import id.homebase.api.client.payload.PayloadLocation
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPreloadService
import id.homebase.api.video.VideoPreloader
import org.koin.compose.koinInject

/** Warms the payload cache the player reads, on an app-scoped coroutine, so leaving the screen doesn't cancel it. */
@Composable
fun VideoPreloadEffect(
    data: VideoPlayerData,
    onPreloading: ((Boolean) -> Unit)? = null,
    onProgress: ((Float) -> Unit)? = null,
) {
    if (data.location is PayloadLocation.Peer) return
    val preloadService = koinInject<VideoPreloadService>()
    val preloader = koinInject<VideoPreloader>()
    LaunchedEffect(data.fileId, data.payloadKey) {
        preloadService.requestPreload(data)
    }
    if (onPreloading != null || onProgress != null) {
        LaunchedEffect(data.fileId, data.payloadKey) {
            preloader.progressFlow(data.fileId, data.payloadKey).collect { p ->
                onProgress?.invoke(p)
                onPreloading?.invoke(p < 1f)
            }
        }
    }
}
