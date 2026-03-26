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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.koin.compose.koinInject
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors

private sealed interface VpsState {
    data object Loading : VpsState
    data class Playing(val videoPath: String) : VpsState
    data class Error(val message: String) : VpsState
}

@Composable
actual fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier,
) {
    val driveFileProvider = koinInject<DriveFileProvider>()
    var state by remember(data) { mutableStateOf<VpsState>(VpsState.Loading) }
    var tempDir by remember(data) { mutableStateOf<File?>(null) }
    var httpServer by remember(data) { mutableStateOf<HttpServer?>(null) }

    DisposableEffect(data) {
        onDispose {
            httpServer?.stop(0)
            tempDir?.deleteRecursively()
        }
    }

    LaunchedEffect(data) {
        withContext(Dispatchers.IO) {
            try {
                val dir = File(System.getProperty("java.io.tmpdir"), "hbvid_${UUID.randomUUID()}")
                    .also { it.mkdirs() }
                tempDir = dir

                when (val content = resolveVideoContent(data, driveFileProvider)) {
                    is VideoContent.Hls -> {
                        File(dir, "index.m3u8").writeText(content.strippedPlaylist)

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
                        state = VpsState.Playing("http://localhost:${server.address.port}/index.m3u8")
                    }
                    is VideoContent.Mp4 -> {
                        File(dir, "video.mp4").writeBytes(content.bytes)
                        state = VpsState.Playing(File(dir, "video.mp4").absolutePath)
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
            is VpsState.Playing -> VlcjPlayer(videoPath = s.videoPath, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun VlcjPlayer(
    videoPath: String,
    modifier: Modifier,
    onFirstFrameRendered: () -> Unit = {},
) {
    val vlcFound = remember { NativeDiscovery().discover() }

    if (!vlcFound) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("VLC is required for video playback.\nInstall it from videolan.org and restart the app.")
        }
        return
    }

    val factory = remember { MediaPlayerFactory() }
    val mediaPlayer = remember { factory.mediaPlayers().newEmbeddedMediaPlayer() }

    // VLC delivers frames on its own thread. We park the latest frame in an AtomicReference
    // and pull it into Compose state via withFrameNanos, decoupling VLC's frame rate from
    // the recomposition rate. Bitmap and pixel buffer are reused across frames to avoid GC.
    val pendingFrame = remember { AtomicReference<ImageBitmap?>(null) }
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var isPlaying by remember(videoPath) { mutableStateOf(true) }
    var position by remember(videoPath) { mutableFloatStateOf(0f) }
    var duration by remember(videoPath) { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }

    var firstFrameFired by remember(videoPath) { mutableStateOf(false) }

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
                val len = mediaPlayer.status().length().toFloat()
                val pos = mediaPlayer.status().time().toFloat()
                val nowPlaying = mediaPlayer.status().isPlaying
                if (len > 0f) {
                    duration = len
                    position = pos
                }
                if (isPlaying && !nowPlaying && len > 0f && pos >= len - 600) {
                    // reached end — reset to beginning
                    mediaPlayer.controls().setTime(0)
                    position = 0f
                    isPlaying = false
                } else {
                    isPlaying = nowPlaying
                }
            }
            delay(500)
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
        mediaPlayer.videoSurface().set(surface)
        mediaPlayer.media().play(videoPath)

        onDispose {
            mediaPlayer.controls().stop()
            mediaPlayer.release()
            factory.release()
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val frame = currentFrame
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator()
        }

        Row(
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
