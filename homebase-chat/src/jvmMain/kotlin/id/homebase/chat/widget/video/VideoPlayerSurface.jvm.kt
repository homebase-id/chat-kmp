package id.homebase.chat.widget.video

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
import androidx.compose.ui.awt.SwingPanel
import id.homebase.api.client.drives.files.DriveFileProvider
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import id.homebase.chat.conversationlist.FullScreenOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.io.File
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

    val mediaPlayerComponent = remember { EmbeddedMediaPlayerComponent() }

    DisposableEffect(videoPath) {
        // VLC requires the component to be displayable (attached to an AWT peer) before play().
        // SwingPanel adds it asynchronously, so we wait for the DISPLAYABILITY_CHANGED event.
        var listener: HierarchyListener? = null
        listener = HierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L
                && mediaPlayerComponent.isDisplayable
            ) {
                mediaPlayerComponent.removeHierarchyListener(listener)
                mediaPlayerComponent.mediaPlayer().media().play(videoPath)
            }
        }
        mediaPlayerComponent.addHierarchyListener(listener)

        onDispose {
            mediaPlayerComponent.removeHierarchyListener(listener)
            mediaPlayerComponent.mediaPlayer().controls().stop()
            mediaPlayerComponent.release()
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = { mediaPlayerComponent },
    )
}
