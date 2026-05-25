package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.image.toImageBitmap
import id.homebase.api.video.VideoPlayerData
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.widget.video.VideoPlayerSurface
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import id.homebase.resources.chat_message_video_thumbnail
import id.homebase.resources.moment_video_mute
import id.homebase.resources.moment_video_unmute
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Tap-to-play tile for moments whose payload set is exactly one video. The idle
 * branch shows the same thumbnail + play overlay as [MomentMediaItem]; tapping it
 * flips to [VideoPlayerSurface] in place. Active-playback coordination
 * (one tile playing at a time, pause-on-scroll-off) is owned by the caller.
 */
@Composable
fun MomentInlineVideoTile(
    payload: PayloadDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    previewThumbnail: EmbeddedThumb? = null,
    localContext: LocalAttachmentContext? = null,
    isUploading: Boolean = false,
    isPlaying: Boolean,
    onPlayTap: () -> Unit,
    onDoubleTap: () -> Unit,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier = Modifier,
) {
    val payloadIv = remember(payload.iv) { payload.iv?.let { Base64.decode(it) } }
    if (payloadIv == null) {
        MomentVideoIvMissingFallback(
            payload = payload,
            localContext = localContext,
            modifier = modifier,
        )
        return
    }

    val perPayloadKeyHeader = remember(payloadIv, keyHeader.aesKey) {
        KeyHeader(iv = payloadIv, aesKey = keyHeader.aesKey)
    }
    val videoPlayerData = remember(fileId, driveId, payload.key, perPayloadKeyHeader, payload.descriptorContent) {
        VideoPlayerData(
            fileId = fileId,
            driveId = driveId,
            payloadKey = payload.key,
            keyHeader = perPayloadKeyHeader,
            descriptorContent = payload.descriptorContent,
        )
    }
    val videoDescriptor = remember(payload.descriptorContent) {
        payload.descriptorInfo() as? DescriptorContent.VideoFile
    }
    val isHls = videoDescriptor?.isSegmented == true
    val videoLocalContext = localContext as? LocalAttachmentContext.Video
    val displayDurationMs: Long? = run {
        val ctx = videoLocalContext
        if (ctx != null) {
            val total = ctx.durationMs ?: 0L
            val s = ctx.trimStartMs ?: 0L
            val e = ctx.trimEndMs ?: total
            (e - s).takeIf { it > 0 }
        } else videoDescriptor?.durationMs
    }

    Box(modifier = modifier) {
        if (isPlaying) {
            val fullScreenData = remember(videoPlayerData, payload) {
                FullScreenOverlay.VideoPlayerData(
                    fileId = videoPlayerData.fileId,
                    driveId = videoPlayerData.driveId,
                    payloadKey = videoPlayerData.payloadKey,
                    keyHeader = videoPlayerData.keyHeader,
                    payload = payload,
                )
            }
            VideoPlayerSurface(
                data = fullScreenData,
                modifier = Modifier.fillMaxSize(),
                muted = isMuted,
            )
            val muteLabel = stringResource(
                if (isMuted) MR.string.moment_video_unmute else MR.string.moment_video_mute,
            )
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = muteLabel,
                    tint = Color.White,
                )
            }
        } else {
            var isPreloading by remember(fileId, payload.key) { mutableStateOf(false) }
            var preloadProgress by remember(fileId, payload.key) { mutableFloatStateOf(0f) }
            if (!isUploading) {
                VideoPreloadEffect(
                    data = videoPlayerData,
                    onPreloading = { isPreloading = it },
                    onProgress = { preloadProgress = it },
                )
            }
            val imageData = remember(driveId, fileId, payload.key, payload.lastModified) {
                HomebaseImageData(
                    driveId = driveId,
                    fileId = fileId,
                    payloadKey = payload.key,
                    previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                        ?: previewThumbnail,
                    requestedSize = ImageSize.THUMB_MEDIUM,
                    lastModified = payload.lastModified,
                    isEncrypted = true,
                    keyHeader = perPayloadKeyHeader,
                )
            }
            val gestureModifier = Modifier
                .fillMaxSize()
                .pointerInput(onPlayTap, onDoubleTap) {
                    detectTapGestures(
                        onTap = { onPlayTap() },
                        onDoubleTap = { onDoubleTap() },
                    )
                }
            if (videoLocalContext != null) {
                val uploadBitmap = remember(videoLocalContext.thumbnailBytes) {
                    videoLocalContext.thumbnailBytes.toImageBitmap()
                }
                if (uploadBitmap != null) {
                    Image(
                        bitmap = uploadBitmap,
                        contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                        modifier = gestureModifier,
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                HomebaseImage(
                    imageData = imageData,
                    modifier = gestureModifier,
                    contentScale = ContentScale.Crop,
                    contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
            if (!isUploading) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                    tint = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    text = if (isHls) "HLS" else "MP4",
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                )
                if (displayDurationMs != null) {
                    Text(
                        text = formatDurationLabel(displayDurationMs),
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(
                                Color.Black.copy(alpha = 0.55f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (isPreloading && !isUploading) {
                Box(
                    modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preloadProgress > 0f) {
                        CircularProgressIndicator(
                            progress = { preloadProgress },
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentVideoIvMissingFallback(
    payload: PayloadDescriptor,
    localContext: LocalAttachmentContext?,
    modifier: Modifier,
) {
    val videoCtx = localContext as? LocalAttachmentContext.Video
    val imageBitmap = videoCtx?.thumbnailBytes?.let { bytes ->
        remember(bytes) { bytes.toImageBitmap() }
    }
    val durationMs: Long? = videoCtx?.let {
        val total = it.durationMs ?: 0L
        val s = it.trimStartMs ?: 0L
        val e = it.trimEndMs ?: total
        (e - s).takeIf { d -> d > 0 }
    }
    if (imageBitmap != null) {
        Box(modifier = modifier) {
            Image(
                bitmap = imageBitmap,
                contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (durationMs != null) {
                Text(
                    text = formatDurationLabel(durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(
                            Color.Black.copy(alpha = 0.55f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    } else {
        Box(modifier = modifier)
    }
}
