package id.homebase.chat.widget.video

import android.net.Uri
import android.util.Log
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.uuid.Uuid

private sealed interface VpsState {
    data object Loading : VpsState
    data object Ready : VpsState
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
) {
    val context = LocalContext.current
    val driveFileProvider = koinInject<DriveFileProvider>()
    val videoPreloader = koinInject<VideoPreloader>()
    val scope = rememberCoroutineScope()
    var state by remember(data) { mutableStateOf<VpsState>(VpsState.Loading) }
    var tempDir by remember(data) { mutableStateOf<File?>(null) }
    var exoPlayer by remember(data) { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(data) {
        onDispose {
            exoPlayer?.release()
            tempDir?.let { dir ->
                dir.parent?.let { parent -> safeDeleteRecursively(parent, dir.name) }
            }
        }
    }

    LaunchedEffect(exoPlayer, muted) {
        exoPlayer?.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(data) {
        val clickMark = TimeSource.Monotonic.markNow()
        onProgress(0f)

        // Build ExoPlayer on main thread but deferred to after the first frame,
        // avoiding a synchronous block during composition/animation.
        val (player, playerInitElapsed) = measureTimedValue {
            ExoPlayer.Builder(context).build().apply {
                playWhenReady = true
                volume = if (muted) 0f else 1f
            }
        }
        Logger.d(tag = "VideoIO") { "ExoPlayer init: $playerInitElapsed" }
        exoPlayer = player

        withContext(Dispatchers.IO) {
            try {
                when (val content = resolveVideoContent(VideoPlayerData(data.fileId, data.driveId, data.payloadKey, data.keyHeader, data.payload.descriptorContent), driveFileProvider, onDownloadProgress = { onProgress(it * 0.5f) })) {
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
                            player.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    if (playbackState == Player.STATE_READY) {
                                        Logger.d(tag = "VideoIO") { "HLS prepare→STATE_READY: ${prepareStart.elapsedNow()}  |  total click→ready: ${clickMark.elapsedNow()}" }
                                        progressJob.cancel()
                                        onProgress(1f)
                                        player.removeListener(this)
                                    }
                                }
                            })
                            player.prepare()
                            state = VpsState.Ready
                        }
                    }
                    is VideoContent.Mp4 -> {
                        onProgress(0.5f)
                        val file = run {
                            val preloadedPath = videoPreloader.awaitPreloadedFile(data.fileId, data.payloadKey)
                            if (preloadedPath != null) {
                                Logger.d(tag = "VideoIO") { "mp4 using preloaded file" }
                                File(preloadedPath)
                            } else {
                                val dir = File(context.cacheDir, "hbvid_${UUID.randomUUID()}").also { it.mkdirs() }
                                tempDir = dir
                                val (f, writeElapsed) = measureTimedValue {
                                    File(dir, "video.mp4").also { it.writeBytes(content.bytes) }
                                }
                                Logger.d(tag = "VideoIO") { "mp4 temp-file write: ${content.bytes.size} bytes in $writeElapsed" }
                                f
                            }
                        }
                        withContext(Dispatchers.Main) {
                            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                            onProgress(0.8f)
                            val prepareStart = TimeSource.Monotonic.markNow()
                            player.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    if (playbackState == Player.STATE_READY) {
                                        Logger.d(tag = "VideoIO") { "mp4 prepare→STATE_READY: ${prepareStart.elapsedNow()}  |  total click→ready: ${clickMark.elapsedNow()}" }
                                        onProgress(1f)
                                        player.removeListener(this)
                                    }
                                }
                            })
                            player.prepare()
                            state = VpsState.Ready
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoPlayer", "Playback error for fileId=${data.fileId}", e)
                state = VpsState.Error(e.message ?: "Playback error")
            }
        }
    }

    Box(modifier) {
        when (val s = state) {
            VpsState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is VpsState.Error -> Text(text = s.message, color = Color.White, modifier = Modifier.align(Alignment.Center))
            VpsState.Ready -> AndroidView(
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
                        }
                },
                update = { view ->
                    view.useController = useNativeControls
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
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
