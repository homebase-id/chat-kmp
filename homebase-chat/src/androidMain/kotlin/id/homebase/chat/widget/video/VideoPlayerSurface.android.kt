@file:OptIn(UnstableApi::class)

package id.homebase.chat.widget.video

import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlinx.coroutines.Dispatchers
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

@Composable
actual fun VideoPlayerSurface(
    data: FullScreenOverlay.VideoPlayerData,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val driveFileProvider = koinInject<DriveFileProvider>()
    var state by remember(data) { mutableStateOf<VpsState>(VpsState.Loading) }
    var tempDir by remember(data) { mutableStateOf<File?>(null) }

    val exoPlayer = remember(data) {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    DisposableEffect(data) {
        onDispose {
            exoPlayer.release()
            tempDir?.deleteRecursively()
        }
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

                val dir = File(context.cacheDir, "hbvid_${UUID.randomUUID()}").also { it.mkdirs() }
                tempDir = dir

                val hlsPlaylist = metadata.hlsPlaylist
                if (metadata.isSegmented && hlsPlaylist != null) {
                    val strippedPlaylist = hlsPlaylist.lines()
                        .filter { !it.startsWith("#EXT-X-KEY") }
                        .joinToString("\n")

                    val dataSourceFactory = DataSource.Factory {
                        HomebaseVideoDataSource(
                            strippedPlaylist = strippedPlaylist,
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
                        exoPlayer.setMediaSource(mediaSource)
                        exoPlayer.prepare()
                        state = VpsState.Ready
                    }
                } else {
                    val bytesResponse = driveFileProvider.getPayloadBytesDecrypted(
                        driveId = data.driveId,
                        fileId = data.fileId,
                        key = data.payloadKey,
                        keyHeader = data.keyHeader,
                    ) ?: run {
                        state = VpsState.Error("Failed to download video")
                        return@withContext
                    }
                    val file = File(dir, "video.mp4").also { it.writeBytes(bytesResponse.bytes) }
                    withContext(Dispatchers.Main) {
                        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                        exoPlayer.prepare()
                        state = VpsState.Ready
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
            VpsState.Ready -> AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply { player = exoPlayer }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

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

    override fun open(dataSpec: DataSpec): Long {
        openedUri = dataSpec.uri
        val path = dataSpec.uri.path?.trimStart('/') ?: ""
        buffer = if (path.endsWith(".m3u8")) {
            strippedPlaylist.toByteArray()
        } else {
            val chunkStart = dataSpec.position
            val chunkLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) null else dataSpec.length
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
