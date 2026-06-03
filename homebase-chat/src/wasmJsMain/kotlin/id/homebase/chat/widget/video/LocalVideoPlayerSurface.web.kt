@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.chat.widget.video

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import id.homebase.api.file.systemFileSystem
import kotlin.io.encoding.Base64
import okio.Path.Companion.toPath

/*
 * Web local-file video playback. Unlike the server surface (VideoPlayerSurface.web.kt) which
 * decrypts a drive payload, here `filePath` is an okio path into the in-memory FakeFileSystem that
 * the attach pipeline materialized the picked bytes into (see AttachmentUploadResolve.web.kt). We
 * read those bytes, wrap them in a Blob object URL, and play them through a real HTML5 <video>
 * overlaid on the Compose canvas — the same DOM-overlay technique as the server surface (a wasmJs
 * <canvas> can't host a <video>). All overlay helpers live in HtmlVideoOverlay.web.kt.
 */

private fun readOkioBytes(path: String): ByteArray? =
    runCatching { systemFileSystem.read(path.toPath()) { readByteArray() } }.getOrNull()

private fun mimeFromPath(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "3gp", "3gpp" -> "video/3gpp"
        else -> "video/mp4"
    }

private class PlayerSrc(val url: String, val createdByUs: Boolean)

/**
 * Resolve [filePath] to a `<video>`-playable URL. A `blob:` URL (the editor's fast path, minted
 * from the picked File) is used directly and NOT owned — its owner revokes it. An okio path is read
 * and wrapped in a fresh, owned blob URL (the only path that base64s; off the interactive hot path).
 */
private fun resolvePlayerSrc(filePath: String): PlayerSrc? {
    if (filePath.startsWith("blob:")) return PlayerSrc(filePath, createdByUs = false)
    val bytes = readOkioBytes(filePath) ?: return null
    return PlayerSrc(bytesToObjectUrl(Base64.encode(bytes), mimeFromPath(filePath)), createdByUs = true)
}

@Composable
actual fun LocalVideoPlayerSurface(
    filePath: String,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit,
) {
    val density = LocalDensity.current.density
    var element by remember(filePath) { mutableStateOf<JsAny?>(null) }
    // Only set when WE created the URL (okio fallback) — a blob: URL passed in is owned elsewhere.
    var createdUrl by remember(filePath) { mutableStateOf<String?>(null) }
    var bounds by remember(filePath) { mutableStateOf<Rect?>(null) }
    var started by remember(filePath) { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        val src = resolvePlayerSrc(filePath) ?: return@LaunchedEffect
        if (src.createdByUs) createdUrl = src.url
        val el = createVideoOverlay(muted = false, controls = true)
        setVideoOverlaySrc(el, src.url)
        element = el
    }

    LaunchedEffect(element, bounds) {
        val el = element ?: return@LaunchedEffect
        val b = bounds ?: return@LaunchedEffect
        val widthCss = (b.width / density).toDouble()
        val heightCss = (b.height / density).toDouble()
        setVideoOverlayBounds(el, (b.left / density).toDouble(), (b.top / density).toDouble(), widthCss, heightCss)
        // Autoplay only once the element has real on-screen bounds. A programmatic unmuted play()
        // can be blocked without a recent user gesture; playVideoOverlay swallows that rejection
        // and the native control bar remains as a fallback.
        if (!started && widthCss > 0.0 && heightCss > 0.0) {
            started = true
            playVideoOverlay(el)
            onFirstFrameRendered()
        }
    }

    DisposableEffect(filePath) {
        onDispose {
            element?.let { removeVideoOverlay(it) }
            createdUrl?.let { revokeObjectUrlJs(it) }
        }
    }

    Box(modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() })
}

@Composable
actual fun TrimmableVideoPlayerSurface(
    filePath: String,
    clipStartMs: Long,
    clipEndMs: Long,
    isPlaying: Boolean,
    seekRequestMs: Long?,
    onPositionMs: (Long) -> Unit,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit,
) {
    val density = LocalDensity.current.density
    var element by remember(filePath) { mutableStateOf<JsAny?>(null) }
    // Only set when WE created the URL (okio fallback) — a blob: URL passed in is owned elsewhere.
    var createdUrl by remember(filePath) { mutableStateOf<String?>(null) }
    var bounds by remember(filePath) { mutableStateOf<Rect?>(null) }
    var firstFrameSent by remember(filePath) { mutableStateOf(false) }

    // The timeupdate listener is registered once but must read the *latest* clip bounds / callback
    // (the trim handles move while the same <video> stays mounted). rememberUpdatedState keeps the
    // closure current without re-registering the DOM listener.
    val clipStartState = rememberUpdatedState(clipStartMs)
    val clipEndState = rememberUpdatedState(clipEndMs)
    val onPositionState = rememberUpdatedState(onPositionMs)

    LaunchedEffect(filePath) {
        val src = resolvePlayerSrc(filePath) ?: return@LaunchedEffect
        if (src.createdByUs) createdUrl = src.url
        // No native controls — the trim screen draws its own scrubber.
        val el = createVideoOverlay(muted = false, controls = false)
        addVideoOverlayProgressListener(el) { currentSec, _ ->
            val ms = (currentSec * 1000).toLong()
            onPositionState.value(ms)
            // Loop within [clipStart, clipEnd]: when playback runs past the clip end, jump back
            // to the clip start (matches the native trim players' looping contract).
            val end = clipEndState.value
            if (end > 0L && ms >= end) {
                setVideoOverlayCurrentTime(el, clipStartState.value / 1000.0)
            }
        }
        // Seek to the clip start once the first frame is decoded (assigning currentTime before
        // metadata is loaded is unreliable).
        addVideoOverlayLoadedListener(el) {
            setVideoOverlayCurrentTime(el, clipStartState.value / 1000.0)
        }
        setVideoOverlaySrc(el, src.url)
        element = el
    }

    LaunchedEffect(element, bounds) {
        val el = element ?: return@LaunchedEffect
        val b = bounds ?: return@LaunchedEffect
        val widthCss = (b.width / density).toDouble()
        val heightCss = (b.height / density).toDouble()
        setVideoOverlayBounds(el, (b.left / density).toDouble(), (b.top / density).toDouble(), widthCss, heightCss)
        if (!firstFrameSent && widthCss > 0.0 && heightCss > 0.0) {
            firstFrameSent = true
            onFirstFrameRendered()
        }
    }

    LaunchedEffect(element, isPlaying) {
        val el = element ?: return@LaunchedEffect
        if (isPlaying) playVideoOverlay(el) else pauseVideoOverlay(el)
    }

    // External seek: the trim screen passes a fresh value (or null) whenever it wants the playhead
    // moved (e.g. after dragging a handle).
    LaunchedEffect(element, seekRequestMs) {
        val el = element ?: return@LaunchedEffect
        val ms = seekRequestMs ?: return@LaunchedEffect
        setVideoOverlayCurrentTime(el, ms / 1000.0)
    }

    DisposableEffect(filePath) {
        onDispose {
            element?.let { removeVideoOverlay(it) }
            createdUrl?.let { revokeObjectUrlJs(it) }
        }
    }

    Box(modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() })
}
