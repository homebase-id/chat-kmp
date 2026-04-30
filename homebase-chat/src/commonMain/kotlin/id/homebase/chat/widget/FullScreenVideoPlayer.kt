package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.KeyHeader
import id.homebase.api.image.toImageBitmap
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.widget.video.LocalVideoPlayerSurface
import id.homebase.chat.widget.video.VideoPlayerSurface
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import id.homebase.resources.chat_message_play_video
import id.homebase.resources.chat_message_video_thumbnail
import id.homebase.resources.chat_options
import id.homebase.resources.menu_back
import id.homebase.resources.upload_compressing
import id.homebase.resources.upload_done
import id.homebase.resources.upload_preparing
import id.homebase.resources.upload_sending
import id.homebase.resources.upload_uploading
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenVideoPlayer(
    data: FullScreenOverlay.VideoPlayerData,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    isDownloading: Boolean = false,
    modifier: Modifier = Modifier,
    uploadStatus: UploadStatus? = null,
) {
    var isPlaying by remember(data) { mutableStateOf(true) }
    var progress by remember(data) { mutableFloatStateOf(0f) }
    var showMenu by remember { mutableStateOf(false) }

    val payloadIv = remember(data.payload.iv) {
        data.payload.iv?.let { Base64.decode(it) }
    }
    val isLocalPlayback = data.localFilePath != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video player surface - prefer local file when available
        if (isLocalPlayback) {
            LocalVideoPlayerSurface(
                filePath = data.localFilePath,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            VideoPlayerSurface(
                data = data,
                modifier = Modifier.fillMaxSize(),
                onProgress = { progress = it },
            )
        }

        // Download/playback progress (only for server-based playback)
        if (!isLocalPlayback && progress < 1f) {
            val progressString = "${(progress * 100).toInt()}%"
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
                Text(
                    text = progressString,
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }

        // Thumbnail + play button overlay, hidden once user taps play
        if (!isPlaying) {
            if (payloadIv != null) {
                val imageData = remember(
                    data.driveId,
                    data.fileId,
                    data.payloadKey,
                    data.payload.lastModified
                ) {
                    HomebaseImageData(
                        driveId = data.driveId,
                        fileId = data.fileId,
                        payloadKey = data.payloadKey,
                        previewThumbnail = data.payload.previewThumbnail?.toEmbeddedThumb(),
                        requestedSize = ImageSize.THUMB_MEDIUM,
                        lastModified = data.payload.lastModified,
                        isEncrypted = true,
                        keyHeader = KeyHeader(iv = payloadIv, aesKey = data.keyHeader.aesKey)
                    )
                }
                HomebaseImage(
                    imageData = imageData,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                )
            } else if (isLocalPlayback) {
                // Show local thumbnail when paused during local playback
                val localVideoContextStore = org.koin.compose.koinInject<LocalAttachmentContextStore>()
                val localContext = data.uploadMessageId?.let {
                    localVideoContextStore.get(it, data.payloadKey) as? LocalAttachmentContext.Video
                }
                val localBitmap = localContext?.thumbnailBytes?.let { bytes ->
                    remember(bytes) { bytes.toImageBitmap() }
                }
                if (localBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = localBitmap,
                        contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = stringResource(MR.string.chat_message_play_video),
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.Center)
                    .clickable { isPlaying = true },
                tint = Color.White.copy(alpha = 0.85f)
            )
        }

        TopAppBar(
            modifier = Modifier.align(Alignment.TopStart),
            title = {},
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = stringResource(MR.string.menu_back),
                        tint = Color.White
                    )
                }
            },
            actions = {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
                if (uploadStatus != null) {
                    val statusText = when (uploadStatus) {
                        UploadStatus.Preparing -> stringResource(MR.string.upload_preparing)
                        is UploadStatus.Processing -> "${stringResource(MR.string.upload_compressing)} ${(uploadStatus.progress * 100).toInt()}%"
                        UploadStatus.Sending -> stringResource(MR.string.upload_sending)
                        is UploadStatus.Uploading -> "${stringResource(MR.string.upload_uploading)} ${(uploadStatus.progress * 100).toInt()}%"
                        UploadStatus.Completed -> stringResource(MR.string.upload_done)
                    }
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                if (!isLocalPlayback) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(MR.string.chat_options),
                                tint = Color.White
                            )
                        }
                        FullScreenMediaMenu(
                            showMenu = showMenu,
                            dismissMenu = { showMenu = false },
                            onSave = {
                                showMenu = false
                                onSave()
                            },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}
