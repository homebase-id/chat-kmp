package id.homebase.chat.widget.video

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.util.concurrent.atomic.AtomicReference
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.koin.compose.koinInject
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

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

    DisposableEffect(data) {
        onDispose { tempDir?.deleteRecursively() }
    }

    LaunchedEffect(data) {
        withContext(Dispatchers.IO) {
            try {
                val metadata = data.payload.descriptorContent?.let {
                    OdinSystemSerializer.deserialize<VideoMetadata>(it)
                } ?: run {
                    state = VpsState.Error("Missing video metadata")
                    return@withContext
                }

                val bytesResponse = driveFileProvider.getPayloadBytesDecrypted(
                    driveId = data.driveId,
                    fileId = data.fileId,
                    key = data.payloadKey,
                    keyHeader = data.keyHeader,
                ) ?: run {
                    state = VpsState.Error("Failed to download video")
                    return@withContext
                }

                val dir = File(System.getProperty("java.io.tmpdir"), "hbvid_${UUID.randomUUID()}")
                    .also { it.mkdirs() }
                tempDir = dir

                val hlsPlaylist = metadata.hlsPlaylist
                if (metadata.isSegmented && hlsPlaylist != null) {
                    File(dir, "index.ts").writeBytes(bytesResponse.bytes)
                    File(dir, "enc.key").writeBytes(data.keyHeader.aesKey.unsafeBytes)
                    File(dir, "index.m3u8").writeText(hlsPlaylist)
                    state = VpsState.Playing(File(dir, "index.m3u8").absolutePath)
                } else {
                    File(dir, "video.mp4").writeBytes(bytesResponse.bytes)
                    state = VpsState.Playing(File(dir, "video.mp4").absolutePath)
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
private fun VlcjPlayer(
    videoPath: String,
    modifier: Modifier,
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

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { pendingFrame.getAndSet(null)?.let { currentFrame = it } }
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
    }
}
