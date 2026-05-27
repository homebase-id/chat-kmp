@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.chat.widget.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.video.VideoContent
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.resolveVideoContent
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.resources.MR
import id.homebase.resources.video_web_large_playback_unsupported
import kotlin.io.encoding.Base64
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private sealed interface WebVps {
    data object Loading : WebVps
    data object Mp4Playing : WebVps
    data object HlsUnsupported : WebVps
    data class Error(val message: String) : WebVps
}

/**
 * Web video playback: decrypt the (already warm-cached) payload and play it through a real HTML
 * `<video>` overlaid on the Compose canvas (see [createVideoOverlay]). MP4/compress-path clips
 * play; >5 MB HLS clips show a "not yet" message rather than a dark, stuck player (deferred —
 * `FFmpegUtils.remuxHlsToMp4` exists for a future follow-up).
 */
@Composable
actual fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier,
    onProgress: (Float) -> Unit,
    muted: Boolean,
    @Suppress("UNUSED_PARAMETER") useNativeControls: Boolean,
    @Suppress("UNUSED_PARAMETER") useInlineOptimizations: Boolean,
    @Suppress("UNUSED_PARAMETER") startPositionMs: Long,
    @Suppress("UNUSED_PARAMETER") onPositionUpdate: (Long) -> Unit,
) {
    val driveFileProvider = koinInject<DriveFileProvider>()
    val density = LocalDensity.current.density

    var state by remember(data) { mutableStateOf<WebVps>(WebVps.Loading) }
    var element by remember(data) { mutableStateOf<JsAny?>(null) }
    var objectUrl by remember(data) { mutableStateOf<String?>(null) }
    var bounds by remember(data) { mutableStateOf<Rect?>(null) }
    var started by remember(data) { mutableStateOf(false) }

    LaunchedEffect(data) {
        onProgress(0f)
        try {
            val content = resolveVideoContent(
                VideoPlayerData(
                    data.fileId,
                    data.driveId,
                    data.payloadKey,
                    data.keyHeader,
                    data.payload.descriptorContent,
                ),
                driveFileProvider,
                onDownloadProgress = { onProgress(it * 0.9f) },
            )
            when (content) {
                is VideoContent.Mp4 -> {
                    val mime = content.metadata.mimeType.ifBlank { "video/mp4" }
                    val url = bytesToObjectUrl(Base64.encode(content.bytes), mime)
                    objectUrl = url
                    val el = createVideoOverlay(muted)
                    addVideoOverlayProgressListener(el) { _, _ -> onProgress(1f) }
                    setVideoOverlaySrc(el, url)
                    // NOTE: do NOT play here — the element has no on-screen bounds yet, so it
                    // would play through invisibly and end before it's ever shown. Play is kicked
                    // off from the bounds effect below, once the element has real (non-zero) bounds.
                    element = el
                    state = WebVps.Mp4Playing
                    onProgress(1f)
                }

                is VideoContent.Hls -> {
                    // >5 MB clips ship as encrypted HLS; a native browser <video> can't play an
                    // .m3u8 blob and we don't remux on web yet. Show a message instead of a dark
                    // stuck player.
                    state = WebVps.HlsUnsupported
                    onProgress(1f)
                }
            }
        } catch (e: Exception) {
            state = WebVps.Error(e.message ?: "Playback error")
            onProgress(1f)
        }
    }

    // Keep the DOM <video> aligned with this composable's on-screen bounds — runs once the element
    // exists and again on every layout change. Compose px -> CSS px via density. When the surface
    // is ~full viewport (the full-screen player), reserve the top app-bar height so its back/menu
    // stay tappable, since the DOM video always paints above the Compose canvas.
    LaunchedEffect(element, bounds) {
        val el = element ?: return@LaunchedEffect
        val b = bounds ?: return@LaunchedEffect
        val leftCss = (b.left / density).toDouble()
        var topCss = (b.top / density).toDouble()
        val widthCss = (b.width / density).toDouble()
        var heightCss = (b.height / density).toDouble()
        if (heightCss >= viewportHeightPx() * 0.8) {
            val appBarInset = 56.0
            topCss += appBarInset
            heightCss -= appBarInset
        }
        setVideoOverlayBounds(el, leftCss, topCss, widthCss, heightCss)
        // Autoplay only once the element is actually on screen. Native controls remain as a
        // fallback if the browser blocks the programmatic play() (no recent user gesture).
        if (!started && widthCss > 0.0 && heightCss > 0.0) {
            started = true
            playVideoOverlay(el)
        }
    }

    DisposableEffect(data) {
        onDispose {
            element?.let { removeVideoOverlay(it) }
            objectUrl?.let { revokeObjectUrlJs(it) }
        }
    }

    Box(modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
        when (val s = state) {
            WebVps.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            WebVps.Mp4Playing -> Unit // the DOM <video> renders over this Box
            WebVps.HlsUnsupported -> Text(
                text = stringResource(MR.string.video_web_large_playback_unsupported),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )

            is WebVps.Error -> Text(
                text = s.message,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}
