package id.homebase.chat.widget.video

import android.net.Uri
import android.view.LayoutInflater
import co.touchlab.kermit.Logger
import id.homebase.chat.R
import kotlin.time.measureTimedValue
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.safeDeleteRecursively
import id.homebase.api.video.VideoContent
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPreloader
import id.homebase.api.video.resolveVideoContent
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.uuid.Uuid

private sealed interface VpsState {
    /** Building player and resolving content; no PlayerView mounted yet. */
    data object Loading : VpsState

    /**
     * Media source has been handed to ExoPlayer and `prepare()` was called.
     * PlayerView mounts here so the player has a surface to render to —
     * `onRenderedFirstFrame` cannot fire without one.
     *
     * A loading spinner overlays the (still-transparent) TextureView until the
     * first decoded frame is painted. This mirrors Signal's
     * `onPlaybackReady()` callback: the still poster (in our case the
     * thumbnail laid down by [id.homebase.core.ui.screens.moments.widget.MomentInlineVideoTile])
     * stays the visible content until a real video pixel exists.
     */
    data object Active : VpsState
    data class Error(val message: String) : VpsState
}

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier,
    onProgress: (Float) -> Unit,
    muted: Boolean,
    useNativeControls: Boolean,
    @Suppress("UNUSED_PARAMETER") useInlineOptimizations: Boolean,
    startPositionMs: Long,
    onPositionUpdate: (Long) -> Unit,
    onFirstFrame: () -> Unit,
    useZoomFill: Boolean,
    onEnded: () -> Unit,
    replayToken: Int,
    paused: Boolean,
) {
    // useInlineOptimizations is a no-op on Android — the player pool, audio
    // track disable, and first-frame paint already apply unconditionally to
    // every caller of this actual (see ExoPlayerPool / VpsState.Active /
    // setTrackTypeDisabled below). The flag exists so the iOS actual can
    // gate the same optimizations behind it for a phased dark-launch.
    val context = LocalContext.current
    val driveFileProvider = koinInject<DriveFileProvider>()
    val fileOperationsProvider = koinInject<id.homebase.api.file.FileOperationsProvider>()
    val videoPreloader = koinInject<VideoPreloader>()
    val playerPool = koinInject<ExoPlayerPool>()
    val scope = rememberCoroutineScope()
    var state by remember(data) { mutableStateOf<VpsState>(VpsState.Loading) }
    var firstFramePainted by remember(data) { mutableStateOf(false) }
    var tempDir by remember(data) { mutableStateOf<File?>(null) }
    // Streamed-to-file MP4 temp (hbvid_res_*, #845) — the surface owns deleting
    // it on dispose; the startup sweep is the backstop.
    var tempFile by remember(data) { mutableStateOf<File?>(null) }
    var exoPlayer by remember(data) { mutableStateOf<ExoPlayer?>(null) }
    // The PlayerView is owned by AndroidView's factory; we keep a reference so
    // we can detach the player from it on dispose before returning the player
    // to the pool — otherwise the recycled player would keep pushing frames to
    // a stale TextureView surface.
    var playerView by remember(data) { mutableStateOf<PlayerView?>(null) }
    // Drives keep-screen-awake off the real ExoPlayer play state (#1025). Set in
    // the diagnostics listener's onIsPlayingChanged; consumed by KeepScreenOn below.
    var isPlayingState by remember(data) { mutableStateOf(false) }
    // The per-content-type first-frame listener (added in the HLS/MP4 branches
    // below) self-removes once `onRenderedFirstFrame` fires — but a surface
    // torn down BEFORE first frame (e.g. a fast swipe, or the autoplay gate
    // flapping) never reaches that callback, so without a dispose-time removal
    // the listener leaks onto the recycled pooled player. Leaked listeners then
    // accumulate and all fire `onFirstFrame`/`onProgress` against stale,
    // already-disposed surfaces. We keep the live listener here so `onDispose`
    // can detach it alongside the diagnostics listener.
    var firstFrameListener by remember(data) { mutableStateOf<Player.Listener?>(null) }

    // Persistent diagnostics listener — fires for the entire lifetime of the
    // player in this composition. The per-content-type listeners below remove
    // themselves after first frame (they exist to time first-paint), so without
    // this listener any error or state change *after* first frame would be
    // silent. Critical for diagnosing "video plays for a second then stops",
    // "spinner forever", and codec/HLS-decryption failures.
    val diagnosticsListener = remember(data) {
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Logger.e(tag = "VideoIO", throwable = error) {
                    "player error: fileId=${data.fileId} key=${data.payloadKey} code=${error.errorCodeName} message=${error.message}"
                }
            }
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (error == null) {
                    Logger.d(tag = "VideoIO") {
                        "player error cleared: fileId=${data.fileId} key=${data.payloadKey}"
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                Logger.d(tag = "VideoIO") {
                    "player state: fileId=${data.fileId} key=${data.payloadKey} → ${playbackStateName(playbackState)}"
                }
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Logger.d(tag = "VideoIO") {
                    "player isPlaying=$isPlaying fileId=${data.fileId} key=${data.payloadKey}"
                }
                // Keep the screen awake only while actually playing (#1025); the
                // KeepScreenOn effect below holds FLAG_KEEP_SCREEN_ON on the window
                // and releases it the instant playback pauses/ends or on dispose.
                isPlayingState = isPlaying
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Logger.d(tag = "VideoIO") {
                    "player video size: ${videoSize.width}x${videoSize.height} fileId=${data.fileId} key=${data.payloadKey}"
                }
            }
        }
    }

    // Hold the screen awake on the window while this surface is actively playing (#1025).
    KeepScreenOn(isPlayingState)

    DisposableEffect(data) {
        onDispose {
            val p = exoPlayer
            Logger.d(tag = "VideoIO") {
                "surface dispose: fileId=${data.fileId} key=${data.payloadKey} hadPlayer=${p != null}"
            }
            isPlayingState = false
            playerView?.player = null
            if (p != null) {
                // Detach BOTH listeners BEFORE returning the player to the
                // pool. ExoPlayerPool.resetForNextUse only clears media items +
                // playWhenReady; it does not clear listeners. Without these
                // removals the recycled player would keep firing diagnostics
                // callbacks holding a stale `data` closure, and any first-frame
                // listener leaked from a tear-down-before-first-frame would fire
                // `onFirstFrame` against this already-disposed surface.
                firstFrameListener?.let { p.removeListener(it) }
                p.removeListener(diagnosticsListener)
                playerPool.release(p)
            }
            tempDir?.let { dir ->
                dir.parent?.let { parent -> safeDeleteRecursively(parent, dir.name) }
            }
            tempFile?.delete()
        }
    }

    // Pump the current playback position back out to the caller every
    // ~500 ms while the player is playing. Used by the moments inline tile
    // to keep MomentsVideoSession's handoff slot fresh, so a tap-to-detail
    // hop picks up at the same timestamp. Stops automatically when the
    // player is null (composable disposing) or when the effect's data key
    // changes (different payload).
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        while (true) {
            val pos = player.currentPosition
            if (pos > 0L) onPositionUpdate(pos)
            delay(500)
        }
    }

    // Mute by zeroing volume AND disabling the audio renderer at track-selection
    // time. Signal does this so an inline tile doesn't even spin up an audio
    // MediaCodec — saves a decoder slot and battery. The full-screen path
    // reuses this surface; when the user unmutes there, the audio track is
    // re-enabled and the renderer reattaches.
    LaunchedEffect(exoPlayer, muted) {
        val player = exoPlayer ?: return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muted)
            .build()
    }

    // Press-and-hold pause-in-place. Toggling playWhenReady keeps the decoded
    // frame on the TextureView (ExoPlayer holds the last frame while paused)
    // and resumes from the same position when released — no surface teardown,
    // no thumbnail flash, no reset to the start.
    LaunchedEffect(exoPlayer, paused) {
        exoPlayer?.playWhenReady = !paused
    }

    // Replay-in-place. The "Watch again" button increments replayToken; we
    // seek the (already-loaded, ended) player back to 0 and resume without
    // tearing the surface down. Guard on > 0 so the initial composition (token
    // 0) doesn't trigger a spurious seek before playback has even begun.
    LaunchedEffect(replayToken) {
        if (replayToken > 0) {
            exoPlayer?.let { p ->
                p.seekTo(0)
                p.playWhenReady = true
                p.play()
            }
        }
    }

    LaunchedEffect(data) {
        val clickMark = TimeSource.Monotonic.markNow()
        onProgress(0f)

        // Lease a player from the warm pool. On a cold pool this still builds a
        // fresh ExoPlayer, but every subsequent inline play in this app session
        // reuses one of the (≤2) pooled instances — saving the ~100–200ms
        // ExoPlayer.Builder cost that used to land on the UI thread every tap.
        val (player, playerInitElapsed) = measureTimedValue {
            playerPool.acquire().apply {
                playWhenReady = true
                volume = if (muted) 0f else 1f
                addListener(diagnosticsListener)
            }
        }
        Logger.d(tag = "VideoIO") {
            "ExoPlayer acquire: $playerInitElapsed fileId=${data.fileId} key=${data.payloadKey} muted=$muted startPosMs=$startPositionMs nativeControls=$useNativeControls"
        }
        exoPlayer = player

        withContext(Dispatchers.IO) {
            try {
                when (val content = resolveVideoContent(VideoPlayerData(data.fileId, data.driveId, data.payloadKey, data.keyHeader, data.payload.descriptorContent), driveFileProvider, fileOps = fileOperationsProvider, onDownloadProgress = { onProgress(it * 0.5f) })) {
                    is VideoContent.Hls -> {
                        // Subscribe to the preloader's live bytes progress BEFORE kicking off the
                        // preload, so StateFlow's initial value and every subsequent emit lands.
                        val progressJob = scope.launch {
                            var highWater = 0f
                            videoPreloader.progressFlow(data.fileId, data.payloadKey).collect { p ->
                                // Logger.d(tag = "VideoIO") { "hls surface progress: fileId=${data.fileId} p=$p" } // floods log — fires per ~4KB HTTP chunk
                                if (p > highWater) highWater = p
                                if (highWater < 1f) onProgress(highWater)
                            }
                        }
                        // Await the preload so the first segment is cached before ExoPlayer starts.
                        // If MediaItem's preload was cancelled when the chat list left composition,
                        // this is the only path that drives real progress — ExoPlayer's own data-source
                        // fetches bypass onDownloadProgress entirely.
                        videoPreloader.preload(
                            VideoPlayerData(data.fileId, data.driveId, data.payloadKey, data.keyHeader, data.payload.descriptorContent)
                        )
                        val dataSourceFactory = DataSource.Factory {
                            HomebaseVideoDataSource(
                                strippedPlaylist = content.originalPlaylist.lines()
                                    .filter { !it.startsWith("#EXT-X-KEY") }
                                    .joinToString("\n"),
                                driveFileProvider = driveFileProvider,
                                driveId = data.driveId,
                                fileId = data.fileId,
                                payloadKey = data.payloadKey,
                                keyHeader = data.keyHeader,
                            )
                        }
                        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri("homebase://video/index.m3u8"))
                        withContext(Dispatchers.Main) {
                            player.setMediaSource(mediaSource)
                            val prepareStart = TimeSource.Monotonic.markNow()
                            val hlsFirstFrameListener = object : Player.Listener {
                                private var progressCompleted = false
                                // Progress completion fires on WHICHEVER of STATE_READY /
                                // first-frame / player-error arrives first: on some
                                // device+codec combos the first frame renders BEFORE the
                                // READY transition, and the old remove-listener-on-first-
                                // frame left nobody around to hear READY — the spinner
                                // froze at its last emit (0% on a warm cache) over a
                                // PLAYING video. An error means READY never comes at all
                                // (e.g. 10-bit AVC High-10 → NO_EXCEEDS_CAPABILITIES),
                                // which used to spin forever.
                                private fun completeProgress(source: String) {
                                    if (progressCompleted) return
                                    progressCompleted = true
                                    Logger.d(tag = "VideoIO") { "HLS progress complete via $source: ${prepareStart.elapsedNow()}  |  total click→ready: ${clickMark.elapsedNow()}" }
                                    progressJob.cancel()
                                    onProgress(1f)
                                }
                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    if (playbackState == Player.STATE_READY) completeProgress("STATE_READY")
                                }
                                override fun onPlayerError(error: PlaybackException) {
                                    completeProgress("player-error")
                                }
                                // Hide the loading spinner the moment a real
                                // pixel has been pushed to the TextureView —
                                // Signal's onPlaybackReady() equivalent. Until
                                // this fires the still thumbnail (drawn
                                // underneath by MomentInlineVideoTile) is what
                                // the user sees, so there is no black flash
                                // between "ready to play" and "playing".
                                override fun onRenderedFirstFrame() {
                                    Logger.d(tag = "VideoIO") { "HLS first-frame: ${clickMark.elapsedNow()}" }
                                    completeProgress("first-frame")
                                    firstFramePainted = true
                                    onFirstFrame()
                                    player.removeListener(this)
                                    firstFrameListener = null
                                }
                            }
                            firstFrameListener = hlsFirstFrameListener
                            player.addListener(hlsFirstFrameListener)
                            // Seek BEFORE prepare so ExoPlayer buffers from
                            // the target position instead of re-buffering
                            // after a post-prepare seek. Used by the moments
                            // feed↔detail playback handoff.
                            if (startPositionMs > 0) player.seekTo(startPositionMs)
                            player.prepare()
                            state = VpsState.Active
                        }
                    }
                    is VideoContent.Mp4Bytes -> error("Mp4Bytes is the web-only variant — resolveVideoContent was given fileOps")
                    is VideoContent.Mp4File -> {
                        onProgress(0.5f)
                        // Already streamed to a disposable hbvid_res_* temp by the
                        // resolver (#845) — no whole-payload RAM buffer, no second
                        // temp-file write. Deleted on dispose (see tempFile).
                        val file = File(content.filePath).also { tempFile = it }
                        withContext(Dispatchers.Main) {
                            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                            onProgress(0.8f)
                            val prepareStart = TimeSource.Monotonic.markNow()
                            val mp4FirstFrameListener = object : Player.Listener {
                                private var progressCompleted = false
                                // Same completion race as the HLS listener above: first
                                // frame can render BEFORE STATE_READY, and the old
                                // remove-on-first-frame lost the READY→100% emit — the
                                // bar froze at exactly 80% over a playing video.
                                private fun completeProgress(source: String) {
                                    if (progressCompleted) return
                                    progressCompleted = true
                                    Logger.d(tag = "VideoIO") { "mp4 progress complete via $source: ${prepareStart.elapsedNow()}  |  total click→ready: ${clickMark.elapsedNow()}" }
                                    onProgress(1f)
                                }
                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    if (playbackState == Player.STATE_READY) completeProgress("STATE_READY")
                                }
                                override fun onPlayerError(error: PlaybackException) {
                                    completeProgress("player-error")
                                }
                                override fun onRenderedFirstFrame() {
                                    Logger.d(tag = "VideoIO") { "mp4 first-frame: ${clickMark.elapsedNow()}" }
                                    completeProgress("first-frame")
                                    firstFramePainted = true
                                    onFirstFrame()
                                    player.removeListener(this)
                                    firstFrameListener = null
                                }
                            }
                            firstFrameListener = mp4FirstFrameListener
                            player.addListener(mp4FirstFrameListener)
                            if (startPositionMs > 0) player.seekTo(startPositionMs)
                            player.prepare()
                            state = VpsState.Active
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(tag = "VideoIO", throwable = e) {
                    "playback setup error: fileId=${data.fileId} key=${data.payloadKey} message=${e.message}"
                }
                state = VpsState.Error(e.message ?: "Playback error")
            }
        }
    }

    Box(modifier) {
        when (val s = state) {
            VpsState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is VpsState.Error -> Text(text = s.message, color = Color.White, modifier = Modifier.align(Alignment.Center))
            VpsState.Active -> {
                AndroidView(
                    factory = { ctx ->
                        // Inflate the layout instead of constructing PlayerView()
                        // directly so we can opt into `surface_type=texture_view`
                        // + transparent shutter. Programmatic construction would
                        // give us the default SurfaceView, which renders as a
                        // black hole until the first frame paints (and ignores
                        // the thumbnail layered beneath this composable in
                        // MomentInlineVideoTile).
                        (LayoutInflater.from(ctx)
                            .inflate(R.layout.homebase_video_player_view, null) as PlayerView)
                            .apply {
                                player = exoPlayer!!
                                // Native PlayerView's tap-to-show-controls captures
                                // every touch, which blocks the carousel pager from
                                // receiving the horizontal drag. Disable when the
                                // host UI provides its own pause affordance (the
                                // moments-feed inline tile in carousel mode).
                                useController = useNativeControls
                                // PlayerView's constructor unconditionally calls
                                // setClickable(true) + setFocusable(true), so even
                                // with useController=false the View still consumes
                                // ACTION_DOWN. Compose treats the down as consumed
                                // and the card-level multi-tap detector on
                                // MomentPostCard never fires for double/triple-
                                // tap-react over the video surface. Flip both off
                                // when we own the gesture stack ourselves.
                                isClickable = useNativeControls
                                isFocusable = useNativeControls
                                // Resize is driven solely by [useZoomFill]:
                                // ZOOM (crop-to-fill, Reels-style) when set, FIT
                                // (whole frame visible, letterbox bars on
                                // mismatched aspects) when not. Inline tiles
                                // request crop-to-fill explicitly (to match the
                                // crop-to-fill thumbnail underneath); the
                                // comments-open shrink passes useZoomFill = false
                                // so the whole frame is revealed above the sheet.
                                resizeMode = if (useZoomFill) {
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                } else {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                                // Leave the underlying TextureView at its
                                // default `isOpaque = true`. The earlier code
                                // here flipped it to false so the thumbnail
                                // drawn *underneath* the player could show
                                // through during decoder warm-up — but the
                                // pre-first-frame texture state isn't cleanly
                                // alpha=0; some Adreno/Mali drivers hand back
                                // uninitialized GL memory and the compositor
                                // reads that garbage as per-pixel alpha,
                                // bleeding thin slices of the thumbnail
                                // *through* the decoded video frame for the
                                // first ~1–2 frames. With an opaque texture
                                // the slices can't happen; the empty/garbage
                                // window is hidden instead by the thumbnail
                                // overlay drawn *over* this surface in
                                // [id.homebase.core.ui.screens.moments.widget.MomentInlineVideoTile]
                                // (controlled by the onFirstFrame callback).
                                playerView = this
                            }
                    },
                    update = { view ->
                        view.useController = useNativeControls
                        view.isClickable = useNativeControls
                        view.isFocusable = useNativeControls
                        view.resizeMode = if (useZoomFill) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Overlay the spinner until ExoPlayer has actually pushed a
                // frame to the TextureView. Player.STATE_READY only means
                // "enough buffered to start" — there is still a measurable gap
                // before the first decoded pixel reaches the surface.
                if (!firstFramePainted) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

private fun playbackStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "UNKNOWN($state)"
}

@UnstableApi
private class HomebaseVideoDataSource(
    private val strippedPlaylist: String,
    private val driveFileProvider: DriveFileProvider,
    private val driveId: Uuid,
    private val fileId: Uuid,
    private val payloadKey: String,
    private val keyHeader: KeyHeader,
) : DataSource {
    private var buffer: ByteArray = ByteArray(0)
    private var readPosition: Int = 0
    private var openedUri: Uri? = null

    @OptIn(UnstableApi::class)
    override fun open(dataSpec: DataSpec): Long {
        openedUri = dataSpec.uri
        val path = dataSpec.uri.path?.trimStart('/') ?: ""
        buffer = if (path.endsWith(".m3u8")) {
            strippedPlaylist.toByteArray()
        } else {
            val chunkStart = dataSpec.position
            val chunkLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) null else dataSpec.length
            Logger.d(tag = "VideoHLS") { "exo chunk request: fileId=$fileId key=$payloadKey chunkStart=$chunkStart chunkLength=$chunkLength path=$path" }
            runBlocking(Dispatchers.IO) {
                driveFileProvider.getPayloadBytesDecrypted(
                    driveId = driveId,
                    fileId = fileId,
                    key = payloadKey,
                    keyHeader = keyHeader,
                    chunkStart = chunkStart,
                    chunkLength = chunkLength,
                )?.bytes
            } ?: throw IOException("Failed to fetch video chunk at position=$chunkStart")
        }
        readPosition = 0
        return buffer.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readPosition >= this.buffer.size) return C.RESULT_END_OF_INPUT
        val bytesToRead = minOf(length, this.buffer.size - readPosition)
        this.buffer.copyInto(buffer, offset, readPosition, readPosition + bytesToRead)
        readPosition += bytesToRead
        return bytesToRead
    }

    override fun getUri(): Uri? = openedUri
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    override fun addTransferListener(transferListener: TransferListener) {}
    override fun close() { buffer = ByteArray(0) }
}
