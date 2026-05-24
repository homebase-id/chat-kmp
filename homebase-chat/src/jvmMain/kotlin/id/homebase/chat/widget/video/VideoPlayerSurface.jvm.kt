package id.homebase.chat.widget.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import com.sun.net.httpserver.HttpServer
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.safeDeleteRecursively
import id.homebase.api.video.VideoContent
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPreloader
import id.homebase.api.video.resolveVideoContent
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.resources.MR
import id.homebase.resources.cd_video_frame
import id.homebase.resources.vlc_required
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Bitmap
import org.koin.compose.koinInject
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.measureTimedValue

private sealed interface VpsState {
    data object Loading : VpsState
    data class Playing(val videoPath: String) : VpsState
    data class Error(val message: String) : VpsState
}

@Composable
actual fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier,
    onProgress: (Float) -> Unit,
    muted: Boolean,
) {
    val driveFileProvider = koinInject<DriveFileProvider>()
    val videoPreloader = koinInject<VideoPreloader>()
    val scope = rememberCoroutineScope()
    var state by remember(data) { mutableStateOf<VpsState>(VpsState.Loading) }
    var tempDir by remember(data) { mutableStateOf<File?>(null) }
    var httpServer by remember(data) { mutableStateOf<HttpServer?>(null) }

    DisposableEffect(data) {
        onDispose {
            httpServer?.stop(0)
            tempDir?.let { dir ->
                dir.parent?.let { parent -> safeDeleteRecursively(parent, dir.name) }
            }
        }
    }

    LaunchedEffect(data) {
        onProgress(0f)
        withContext(Dispatchers.IO) {
            try {
                val dir = File(System.getProperty("java.io.tmpdir"), "hbvid_${UUID.randomUUID()}")
                    .also { it.mkdirs() }
                tempDir = dir

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
                        // Await the preload so the first segment is cached before VLC starts.
                        // If MediaItem's preload was cancelled when the chat list left composition,
                        // this is the only path that drives real progress — VLC's own data-source
                        // fetches bypass onDownloadProgress entirely.
                        videoPreloader.preload(
                            VideoPlayerData(data.fileId, data.driveId, data.payloadKey, data.keyHeader, data.payload.descriptorContent)
                        )
                        File(dir, "index.m3u8").writeText(
                            content.originalPlaylist.lines()
                                .filter { !it.startsWith("#EXT-X-KEY") }
                                .joinToString("\n")
                        )

                        val totalSize = content.metadata.fileSize
                        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
                        createContext("/") { exchange ->
                            val name = exchange.requestURI.path.trimStart('/')
                            if (name.endsWith(".m3u8")) {
                                val fileBytes = File(dir, name).readBytes()
                                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                                exchange.sendResponseHeaders(200, fileBytes.size.toLong())
                                exchange.responseBody.use { it.write(fileBytes) }
                                return@createContext
                            }

                            // .ts range requests — fetch and decrypt the requested chunk on demand
                            exchange.responseHeaders.add("Content-Type", "video/mp2t")
                            exchange.responseHeaders.add("Accept-Ranges", "bytes")
                            val rangeHeader = exchange.requestHeaders.getFirst("Range")
                            val start: Long
                            val end: Long
                            if (rangeHeader != null) {
                                val parts = rangeHeader.removePrefix("bytes=").split("-")
                                start = parts[0].toLong()
                                end = if (parts[1].isNotEmpty()) parts[1].toLong() else totalSize - 1
                            } else {
                                start = 0
                                end = totalSize - 1
                            }
                            val length = end - start + 1
                            Logger.d(tag = "VideoHLS") { "vlc chunk request: fileId=${data.fileId} key=${data.payloadKey} chunkStart=$start chunkLength=$length name=$name" }
                            val bytes = runBlocking {
                                driveFileProvider.getPayloadBytesDecrypted(
                                    driveId = data.driveId,
                                    fileId = data.fileId,
                                    key = data.payloadKey,
                                    keyHeader = data.keyHeader,
                                    chunkStart = start,
                                    chunkLength = length,
                                )?.bytes
                            }
                            if (bytes == null) {
                                exchange.sendResponseHeaders(500, -1)
                                exchange.responseBody.close()
                                return@createContext
                            }
                            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$totalSize")
                            exchange.sendResponseHeaders(206, bytes.size.toLong())
                            exchange.responseBody.use { it.write(bytes) }
                        }
                        executor = Executors.newFixedThreadPool(4)
                        start()
                    }
                        httpServer = server
                        progressJob.cancel()
                        state = VpsState.Playing("http://localhost:${server.address.port}/index.m3u8")
                    }
                    is VideoContent.Mp4 -> {
                        onProgress(0.5f)
                        val preloadedPath = videoPreloader.awaitPreloadedFile(data.fileId, data.payloadKey)
                        val mp4Path = if (preloadedPath != null) {
                            Logger.d(tag = "VideoIO") { "mp4 using preloaded file" }
                            preloadedPath
                        } else {
                            val (mp4File, writeElapsed) = measureTimedValue {
                                File(dir, "video.mp4").also { it.writeBytes(content.bytes) }
                            }
                            Logger.d(tag = "VideoIO") { "mp4 temp-file write: ${content.bytes.size} bytes in $writeElapsed" }
                            mp4File.absolutePath
                        }
                        onProgress(0.8f)
                        state = VpsState.Playing(mp4Path)
                    }
                }
            } catch (e: Exception) {
                state = VpsState.Error(e.message ?: "Playback error")
            }
        }
    }

    Box(modifier) {
        when (val s = state) {
            VpsState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is VpsState.Error -> Text(text = s.message, modifier = Modifier.align(Alignment.Center))
            is VpsState.Playing -> VlcjPlayer(
                videoPath = s.videoPath,
                modifier = Modifier.fillMaxSize(),
                onFirstFrameRendered = { onProgress(1f) },
                muted = muted,
            )
        }
    }
}

@Composable
internal fun VlcjPlayer(
    videoPath: String,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit = {},
    showControls: Boolean = true,
    clipStartMs: Long? = null,
    clipEndMs: Long? = null,
    externalIsPlaying: Boolean? = null,
    seekRequestMs: Long? = null,
    onPositionMs: ((Long) -> Unit)? = null,
    muted: Boolean = false,
) {
    val vlcFound = remember { NativeDiscovery().discover() }

    if (!vlcFound) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(MR.string.vlc_required))
        }
        return
    }

    val factory = remember { MediaPlayerFactory() }
    val mediaPlayer = remember { factory.mediaPlayers().newEmbeddedMediaPlayer() }

    // VLC delivers frames on its own thread. We park the latest frame in an AtomicReference
    // and pull it into Compose state via withFrameNanos, decoupling VLC's frame rate from
    // the recomposition rate. Bitmap and pixel buffer are reused across frames to avoid GC.
    // Re-keyed to videoPath so stale frames don't outlive the Bitmap that backs them
    // when the user scrolls to a different video.
    val pendingFrame = remember(videoPath) { AtomicReference<ImageBitmap?>(null) }
    var currentFrame by remember(videoPath) { mutableStateOf<ImageBitmap?>(null) }
    var isPlaying by remember(videoPath) { mutableStateOf(true) }
    var position by remember(videoPath) { mutableFloatStateOf(0f) }
    var duration by remember(videoPath) { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    var firstFrameFired by remember(videoPath) { mutableStateOf(false) }

    // Updated on VLC's internal event thread; read on the Compose thread.
    val atomicPositionMs = remember { AtomicLong(0L) }
    val atomicDurationMs = remember { AtomicLong(0L) }
    val atomicFinished = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                pendingFrame.getAndSet(null)?.let {
                    currentFrame = it
                    if (!firstFrameFired) {
                        firstFrameFired = true
                        onFirstFrameRendered()
                    }
                }
            }
        }
    }

    LaunchedEffect(videoPath) {
        while (true) {
            if (!isSeeking) {
                val d = atomicDurationMs.get().toFloat()
                val p = atomicPositionMs.get().toFloat()
                if (d > 0f) {
                    duration = d
                    position = p
                }
            }
            if (atomicFinished.getAndSet(false)) {
                // Loop within clip when present, else snap to 0 + pause.
                if (clipStartMs != null) {
                    mediaPlayer.controls().setTime(clipStartMs)
                    position = clipStartMs.toFloat()
                    isPlaying = mediaPlayer.status().isPlaying
                } else {
                    mediaPlayer.controls().setTime(0)
                    position = 0f
                    isPlaying = false
                }
            } else {
                isPlaying = mediaPlayer.status().isPlaying
            }
            onPositionMs?.invoke(atomicPositionMs.get())
            delay(33)
        }
    }

    // External play/pause control (only when caller passes externalIsPlaying)
    if (externalIsPlaying != null) {
        LaunchedEffect(externalIsPlaying) {
            if (externalIsPlaying) mediaPlayer.controls().play()
            else mediaPlayer.controls().setPause(true)
        }
    }

    LaunchedEffect(muted) {
        mediaPlayer.audio().isMute = muted
    }

    // External seek requests
    if (seekRequestMs != null) {
        LaunchedEffect(seekRequestMs) {
            mediaPlayer.controls().setTime(seekRequestMs)
        }
    }

    DisposableEffect(videoPath) {
        // Reused across frames; reallocated only on resolution change.
        var skiaBitmap = Bitmap()
        var pixelBuffer = ByteArray(0)

        val surface = factory.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
                    RV32BufferFormat(sourceWidth, sourceHeight)
                override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
            },
            object : RenderCallback {
                override fun display(
                    mediaPlayer: uk.co.caprica.vlcj.player.base.MediaPlayer,
                    nativeBuffers: Array<ByteBuffer>,
                    bufferFormat: BufferFormat,
                ) {
                    val width = bufferFormat.width
                    val height = bufferFormat.height
                    val size = width * height * 4
                    if (pixelBuffer.size != size) {
                        pixelBuffer = ByteArray(size)
                        // Close the outgoing Bitmap before dropping our only strong reference.
                        // Any ImageBitmap that wrapped it (still held in currentFrame for a frame
                        // or two) will error cleanly on the next draw instead of racing GC.
                        skiaBitmap.close()
                        skiaBitmap = Bitmap().apply { allocN32Pixels(width, height) }
                    }
                    nativeBuffers[0].rewind()
                    nativeBuffers[0].get(pixelBuffer)
                    skiaBitmap.installPixels(pixelBuffer)
                    pendingFrame.set(skiaBitmap.asComposeImageBitmap())
                }
            },
            true,
        )
        val eventListener = object : MediaPlayerEventAdapter() {
            override fun timeChanged(mp: uk.co.caprica.vlcj.player.base.MediaPlayer, newTime: Long) {
                atomicPositionMs.set(newTime)
            }
            override fun lengthChanged(mp: uk.co.caprica.vlcj.player.base.MediaPlayer, newLength: Long) {
                atomicDurationMs.set(newLength)
            }
            override fun finished(mp: uk.co.caprica.vlcj.player.base.MediaPlayer) {
                atomicFinished.set(true)
            }
        }

        mediaPlayer.videoSurface().set(surface)
        mediaPlayer.events().addMediaPlayerEventListener(eventListener)

        // VLC media options: :start-time and :stop-time accept seconds as floats.
        val mediaOpts = buildList {
            if (clipStartMs != null && clipStartMs > 0) {
                add(":start-time=${clipStartMs / 1000.0}")
            }
            if (clipEndMs != null && clipEndMs > 0) {
                add(":stop-time=${clipEndMs / 1000.0}")
            }
        }.toTypedArray()
        if (mediaOpts.isNotEmpty()) {
            mediaPlayer.media().play(videoPath, *mediaOpts)
        } else {
            mediaPlayer.media().play(videoPath)
        }
        mediaPlayer.audio().isMute = muted

        onDispose {
            mediaPlayer.events().removeMediaPlayerEventListener(eventListener)
            mediaPlayer.controls().stop()
            mediaPlayer.release()
            factory.release()
            // release() above joins VLC's render threads, so no more display() calls can land.
            // Safe to close the Bitmap now; also drop any pending frame that still wraps it.
            pendingFrame.set(null)
            skiaBitmap.close()
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val frame = currentFrame
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = stringResource(MR.string.cd_video_frame),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator()
        }

        if (showControls) Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = {
                if (isPlaying) {
                    mediaPlayer.controls().setPause(true)
                } else {
                    mediaPlayer.controls().play()
                }
                isPlaying = !isPlaying
            }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                )
            }
            Text(
                text = formatMs(position.toLong()),
                color = Color.White,
                fontSize = 12.sp,
            )
            SeekBar(
                fraction = if (duration > 0f) position / duration else 0f,
                onSeek = { fraction ->
                    isSeeking = true
                    position = fraction * duration
                    mediaPlayer.controls().setTime(position.toLong())
                },
                onSeekFinished = { isSeeking = false },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatMs(duration.toLong()),
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = Color.White.copy(alpha = 0.4f)
    val fillColor = Color.White
    val thumbRadius = 6.dp

    Box(
        modifier = modifier
            .height(36.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val width = size.width.toFloat()
                    onSeek((down.position.x / width).coerceIn(0f, 1f))
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        change.consume()
                        onSeek((change.position.x / width).coerceIn(0f, 1f))
                    } while (event.changes.any { it.pressed })
                    onSeekFinished()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            val trackY = size.height / 2f
            // Track background
            drawLine(
                color = trackColor,
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = size.height,
            )
            // Filled portion
            drawLine(
                color = fillColor,
                start = Offset(0f, trackY),
                end = Offset(size.width * fraction, trackY),
                strokeWidth = size.height,
            )
            // Thumb
            drawCircle(
                color = fillColor,
                radius = thumbRadius.toPx(),
                center = Offset(size.width * fraction, trackY),
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
