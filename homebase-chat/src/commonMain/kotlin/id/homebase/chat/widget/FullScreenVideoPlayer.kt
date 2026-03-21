package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.video.VideoPlayerSurface
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenVideoPlayer(
    data: FullScreenOverlay.VideoPlayerData,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember(data) { mutableStateOf(false) }

    val payloadIv = remember(data.payload.iv) {
        data.payload.iv?.let { Base64.decode(it) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Always composed so loading/buffering starts immediately on open
        VideoPlayerSurface(
            data = data,
            modifier = Modifier.fillMaxSize(),
        )

        // Thumbnail + play button overlay, hidden once user taps play
        if (!isPlaying) {
            if (payloadIv != null) {
                val imageData = remember(data.driveId, data.fileId, data.payloadKey, data.payload.lastModified) {
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
                    contentDescription = "Video thumbnail",
                )
            }

            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "Play video",
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
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}
