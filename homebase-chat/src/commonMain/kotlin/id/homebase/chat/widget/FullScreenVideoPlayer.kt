package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
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
    var isPlaying by remember(data) { mutableStateOf(true) }
    var progress by remember(data) { mutableFloatStateOf(0f) }

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
            onProgress = { progress = it },
        )

        if (progress < 1f) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(start = 32.dp, end = 32.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

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
