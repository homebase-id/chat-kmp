package id.homebase.chat.widget.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface as ComposeMediaPlayerSurface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val NATIVE_FIRST_FRAME_WATCHDOG_MS = 8_000L

// The dylib's minos is 14.0; Windows uses Media Foundation. Linux needs GStreamer, which the probe finds out.
private fun osHasNativeBackend(): Boolean {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> (System.getProperty("os.version").substringBefore('.').toIntOrNull() ?: 0) >= 14
        else -> true
    }
}

// The state constructor only starts an async init, so the library load is forced here; load() returns false instead of throwing.
// NativeLibraryLoader is internal to the library, hence reflection.
internal var nativeLibraryLoad: () -> Boolean = {
    val loader = Class.forName("io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader")
    loader.getMethod("load", String::class.java, Class::class.java)
        .invoke(loader.getField("INSTANCE").get(null), "NativeVideoPlayer", loader) as Boolean
}

private val nativeBackendProbe: Boolean by lazy { probeNativeBackend() }

internal fun probeNativeBackend(): Boolean =
    osHasNativeBackend() &&
        runCatching { nativeLibraryLoad() }
            .onFailure { Logger.w(tag = "VideoIO", throwable = it) { "native video backend unavailable" } }
            .getOrDefault(false)

internal var nativeBackendAvailable: () -> Boolean = { nativeBackendProbe }

internal enum class DesktopVideoBackend { NATIVE, VLC }

@Composable
internal fun DesktopVideoPlayer(
    videoPath: String,
    modifier: Modifier,
    aspectRatio: Float? = null,
    onFirstFrameRendered: () -> Unit = {},
    showControls: Boolean = true,
    clipStartMs: Long? = null,
    clipEndMs: Long? = null,
    externalIsPlaying: Boolean? = null,
    seekRequestMs: Long? = null,
    onPositionMs: ((Long) -> Unit)? = null,
    muted: Boolean = false,
    onEnded: () -> Unit = {},
    replayToken: Int = 0,
    onBackendChosen: (DesktopVideoBackend) -> Unit = {},
) {
    val nativeOk by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { nativeBackendAvailable() } }
    var useVlc by remember(videoPath) { mutableStateOf(false) }
    val chosen = when {
        nativeOk == null -> null
        nativeOk == false || useVlc -> DesktopVideoBackend.VLC
        else -> DesktopVideoBackend.NATIVE
    }
    val onBackend = rememberUpdatedState(onBackendChosen)
    LaunchedEffect(chosen) { chosen?.let { onBackend.value(it) } }
    when {
        nativeOk == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        nativeOk == false || useVlc -> VlcjPlayer(
            videoPath, modifier, onFirstFrameRendered, showControls, clipStartMs, clipEndMs,
            externalIsPlaying, seekRequestMs, onPositionMs, muted, onEnded, replayToken,
        )
        else -> NativeAvPlayer(
            videoPath, aspectRatio, modifier, onFirstFrameRendered, showControls, clipStartMs, clipEndMs,
            externalIsPlaying, seekRequestMs, onPositionMs, muted, onEnded, replayToken,
            onUnplayable = { useVlc = true },
        )
    }
}

@Composable
internal fun NativeAvPlayer(
    videoPath: String,
    aspectRatio: Float?,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit = {},
    showControls: Boolean = true,
    clipStartMs: Long? = null,
    clipEndMs: Long? = null,
    externalIsPlaying: Boolean? = null,
    seekRequestMs: Long? = null,
    onPositionMs: ((Long) -> Unit)? = null,
    muted: Boolean = false,
    onEnded: () -> Unit = {},
    replayToken: Int = 0,
    onUnplayable: () -> Unit,
    watchdogMs: Long = NATIVE_FIRST_FRAME_WATCHDOG_MS,
) {
    val state = remember(videoPath) { DefaultVideoPlayerState() }
    val ended = remember(videoPath) { AtomicBoolean(false) }
    var firstFrame by remember(videoPath) { mutableStateOf(false) }
    var isPlaying by remember(videoPath) { mutableStateOf(true) }
    var position by remember(videoPath) { mutableFloatStateOf(0f) }
    var duration by remember(videoPath) { mutableFloatStateOf(0f) }
    var isSeeking by remember(videoPath) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mutedState = rememberUpdatedState(muted)
    val onFirstFrame = rememberUpdatedState(onFirstFrameRendered)
    val onEndedState = rememberUpdatedState(onEnded)
    val onPosition = rememberUpdatedState(onPositionMs)
    val onUnplayableState = rememberUpdatedState(onUnplayable)

    fun applyVolume() {
        state.volume = if (mutedState.value) 0f else 1f
    }

    suspend fun seekMs(ms: Long, thenPause: Boolean) {
        val d = state.duration
        if (d <= 0.0) return
        state.seekTo((ms / 1000.0 / d * 1000.0).toFloat().coerceIn(0f, 1000f))
        if (thenPause) {
            // 0.10.0 does not repaint after a paused seek; a brief muted play pulls the new frame in.
            state.volume = 0f
            state.play()
            delay(150)
            state.pause()
            applyVolume()
        }
    }

    DisposableEffect(videoPath) {
        state.onPlaybackEnded = { ended.set(true) }
        applyVolume()
        state.openUri(videoPath, InitialPlayerState.PLAY)
        onDispose { state.dispose() }
    }

    LaunchedEffect(muted) { applyVolume() }

    LaunchedEffect(videoPath) {
        val openedAt = System.currentTimeMillis()
        while (true) {
            if (!firstFrame) {
                if (state.hasMedia && !state.isLoading && state.error == null) {
                    firstFrame = true
                    if (clipStartMs != null && clipStartMs > 0) seekMs(clipStartMs, thenPause = false)
                    onFirstFrame.value()
                } else if (state.error != null || System.currentTimeMillis() - openedAt > watchdogMs) {
                    Logger.w(tag = "VideoIO") { "native player gave no frame (error=${state.error}): $videoPath" }
                    onUnplayableState.value()
                    return@LaunchedEffect
                }
            } else {
                val tMs = (state.currentTime * 1000).toLong()
                val clipEnd = clipEndMs
                if (clipEnd != null && clipEnd > 0 && tMs >= clipEnd) {
                    seekMs(clipStartMs ?: 0L, thenPause = false)
                } else if (ended.getAndSet(false)) {
                    if (clipStartMs != null) {
                        seekMs(clipStartMs, thenPause = false)
                        state.play()
                    } else {
                        isPlaying = false
                        onEndedState.value()
                    }
                }
                if (!isSeeking) {
                    duration = (state.duration * 1000).toFloat()
                    position = tMs.toFloat()
                }
                if (!ended.get()) isPlaying = state.isPlaying
                onPosition.value?.invoke(tMs)
            }
            delay(33)
        }
    }

    if (externalIsPlaying != null) {
        LaunchedEffect(externalIsPlaying, firstFrame) {
            if (!firstFrame) return@LaunchedEffect
            if (externalIsPlaying) state.play() else state.pause()
            isPlaying = externalIsPlaying
        }
    }

    if (seekRequestMs != null) {
        LaunchedEffect(seekRequestMs, firstFrame) {
            if (firstFrame) seekMs(seekRequestMs, thenPause = !state.isPlaying)
        }
    }

    LaunchedEffect(replayToken) {
        if (replayToken > 0) {
            ended.set(false)
            state.restart()
            isPlaying = true
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val surfaceModifier = if (aspectRatio != null) Modifier.aspectRatio(aspectRatio) else Modifier.fillMaxSize()
        Box(surfaceModifier) {
            ComposeMediaPlayerSurface(
                playerState = state,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (aspectRatio != null) ContentScale.FillBounds else ContentScale.Fit,
            )
        }
        if (!firstFrame) CircularProgressIndicator()
        if (showControls && firstFrame) TransportBar(
            isPlaying = isPlaying,
            position = position,
            duration = duration,
            onTogglePlay = {
                if (isPlaying) state.pause() else state.play()
                isPlaying = !isPlaying
            },
            onSeek = { fraction ->
                isSeeking = true
                position = fraction * duration
                state.seekTo(fraction * 1000f)
            },
            onSeekFinished = {
                isSeeking = false
                if (!state.isPlaying) scope.launch { seekMs(position.toLong(), thenPause = true) }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
