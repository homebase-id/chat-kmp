package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import coil3.compose.AsyncImage
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.image.toImageBitmap
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPreloadService
import id.homebase.api.video.VideoPreloader
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import id.homebase.chat.widget.video.formatDurationLabel
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.widget.AudioPlayerWidget
import id.homebase.resources.MR
import id.homebase.resources.chat_message_image_attachment
import id.homebase.resources.chat_message_video_thumbnail
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Composable that renders a single media item based on the payload's content type.
 *
 * Supports:
 * - Images: Full rendering via HomebaseImage
 * - Videos: Placeholder icon (TODO: implement video player)
 * - Audio: Placeholder icon (TODO: implement audio player)
 * - Files: Placeholder icon (TODO: implement file viewer)
 *
 * @param payload The payload descriptor containing media metadata
 * @param fileId The file ID on the Homebase drive
 * @param driveId The drive ID where the file is stored
 * @param previewThumbnail Optional embedded preview thumbnail for instant display
 * @param modifier Modifier for the container
 * @param imageSize Desired image size for loading
 * @param preserveAspectRatio If true, use ContentScale.Fit to maintain aspect ratio; if false, use
 * Crop
 * @param onClick Callback when media is tapped
 * @param onLongPress Callback when media is long-pressed with position
 */
@Composable
fun MediaItem(
    payload: PayloadDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String> = persistentMapOf(),
    modifier: Modifier = Modifier,
    keyHeader: KeyHeader,
    imageSize: ImageSize? = ImageSize.THUMB_MEDIUM,
    preserveAspectRatio: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    onRequestDecryptedFile: (() -> Unit)? = null,
    shape: Shape =
        RoundedCornerShape(
            topStart = Dimens.Message.cornerRadius,
            topEnd = Dimens.Message.cornerRadius
        ),
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    isDownloading: Boolean = false,
    messageId: Uuid? = null,
    isUploading: Boolean = false,
) {
    val contentType = payload.contentType ?: ""
    val imageContentScale = if (preserveAspectRatio) ContentScale.Fit else ContentScale.Crop
    val localVideoContextStore = koinInject<LocalAttachmentContextStore>()
    val localContext = if (messageId != null) {
        val ctx by localVideoContextStore.observe(messageId, payload.key)
            .collectAsStateWithLifecycle(initialValue = localVideoContextStore.get(messageId, payload.key))
        ctx
    } else null

    // Calculate aspect ratio if available
    val aspectRatioThumbnail = payload.thumbnails?.lastOrNull() ?: payload.previewThumbnail
    val aspectRatio =
        remember(aspectRatioThumbnail) {
            val width = aspectRatioThumbnail?.pixelWidth
            val height = aspectRatioThumbnail?.pixelHeight
            if (width != null && height != null && width > 0 && height > 0) {
                width.toFloat() / height.toFloat()
            } else {
                null
            }
        }

    // Base modifier with shape clip
    val baseModifier = modifier.clip(shape)

    // Apply aspect ratio only if needed and available.
    // Note: For aspect ratio to work for "fitting inside", we typically want fillMaxWidth()
    // with aspect ratio, but here we might have width constraints from parent.
    val finalModifier =
        if (preserveAspectRatio && aspectRatio != null) {
            baseModifier.aspectRatio(aspectRatio)
        } else {
            baseModifier
        }

    when {
        payload.key == ChatProtocol.PAYLOAD_KEY_LINKS -> {
            // Render link preview
            val linkDescriptors = remember(payload.descriptorContent) {
                payload.descriptorContent?.let { content ->
                    try {
                        OdinSystemSerializer.deserialize<List<LinkPreviewDescriptor>>(
                            content
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            val payloadIv = payload.iv?.let { Base64.decode(it) }

            if (payloadIv != null && linkDescriptors != null) {
                LinkPreviewCard(
                    descriptor = linkDescriptors[0],
                    fileId = fileId,
                    driveId = driveId,
                    payloadKey = payload.key,
                    keyHeader = KeyHeader(payloadIv, keyHeader.aesKey),
                    previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                        ?: previewThumbnail,
                    modifier = baseModifier,
                )
            } else {
                MediaPlaceholder(
                    emoji = "\uD83D\uDD17",
                    label = "Link",
                    modifier = baseModifier,
                )
            }
        }

        contentType.startsWith("image/") -> {
            val imageLocalContext = localContext as? LocalAttachmentContext.Image
            if (imageLocalContext != null) {
                val gestureModifier = if (onClick != null || onLongPress != null) {
                    finalModifier.pointerInput(onClick, onLongPress) {
                        detectTapGestures(
                            onTap = { onClick?.invoke() },
                            onLongPress = { offset -> onLongPress?.invoke(offset) },
                        )
                    }
                } else {
                    finalModifier
                }
                AsyncImage(
                    model = imageLocalContext.localFilePath,
                    contentDescription = stringResource(MR.string.chat_message_image_attachment),
                    modifier = gestureModifier,
                    contentScale = imageContentScale,
                )
            } else {
                // Render image via HomebaseImage
                // Remember the image data to avoid creating a new instance on every recomposition,
                // which would cause Coil to restart the image loading pipeline and cause flickering.
                val imageData =
                    remember(driveId, fileId, payload.key, payload.lastModified, imageSize) {
                        val payloadIv = payload.iv?.let { Base64.decode(it) } ?: return@remember null
                        HomebaseImageData(
                            driveId = driveId,
                            fileId = fileId,
                            payloadKey = payload.key,
                            previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                                ?: previewThumbnail,
                            requestedSize = imageSize,
                            lastModified = payload.lastModified,
                            isEncrypted = true,
                            keyHeader = KeyHeader(iv = payloadIv, aesKey = keyHeader.aesKey)
                        )
                    }

                if (imageData != null) {
                    HomebaseImage(
                        imageData = imageData,
                        modifier = finalModifier,
                        contentScale = imageContentScale,
                        contentDescription = stringResource(MR.string.chat_message_image_attachment),
                        onClick = onClick,
                        onLongPress = onLongPress,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                } else {
                    // IV not yet available (placeholder during upload prep).
                    // Use a plain box respecting parent constraints — the upload overlay covers it.
                    Box(modifier = finalModifier)
                }
            }
        }

        contentType.startsWith("video/") || contentType == "application/vnd.apple.mpegurl" -> {
            val payloadIv = remember(payload.iv) {
                payload.iv?.let { Base64.decode(it) }
            }
            if (payloadIv != null) {
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
                Box(modifier = finalModifier) {
                    if (videoLocalContext != null) {
                        val uploadBitmap = remember(videoLocalContext.thumbnailBytes) {
                            videoLocalContext.thumbnailBytes.toImageBitmap()
                        }
                        if (uploadBitmap != null) {
                            val thumbBaseModifier = Modifier.fillMaxSize()
                            val thumbModifier = if (onClick != null || onLongPress != null) {
                                thumbBaseModifier.pointerInput(onClick, onLongPress) {
                                    detectTapGestures(
                                        onTap = { onClick?.invoke() },
                                        onLongPress = { offset -> onLongPress?.invoke(offset) },
                                    )
                                }
                            } else {
                                thumbBaseModifier
                            }
                            Image(
                                bitmap = uploadBitmap,
                                contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                                modifier = thumbModifier,
                                contentScale = ContentScale.Crop,
                            )
                        }
                    } else {
                        HomebaseImage(
                            imageData = imageData,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                            onClick = onClick,
                            onLongPress = onLongPress,
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
                            tint = Color.White.copy(alpha = 0.85f)
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
            } else {
                // No IV yet: fall back to local preview if we have one, else placeholder.
                val videoCtx = localContext as? LocalAttachmentContext.Video
                val imageBitmap = videoCtx?.thumbnailBytes?.let { bytes ->
                    remember(bytes) { bytes.toImageBitmap() }
                }
                val noIvDurationMs: Long? = videoCtx?.let {
                    val total = it.durationMs ?: 0L
                    val s = it.trimStartMs ?: 0L
                    val e = it.trimEndMs ?: total
                    (e - s).takeIf { d -> d > 0 }
                }
                if (imageBitmap != null) {
                    Box(modifier = finalModifier) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = stringResource(MR.string.chat_message_video_thumbnail),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        if (noIvDurationMs != null) {
                            Text(
                                text = formatDurationLabel(noIvDurationMs),
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
                    MediaPlaceholder(emoji = "📹", label = "Video", modifier = baseModifier)
                }
            }
        }

        contentType.startsWith("audio/") -> {
            AudioPlayerWidget(
                modifier = baseModifier,
                driveId = driveId,
                fileId = fileId,
                keyHeader = keyHeader,
                audioFile = decryptedFiles[DecryptedFileKey(fileId, payload.key)],
                payload = payload,
                onRequestDecryptedFile = onRequestDecryptedFile,
            )
        }

        contentType == "application/zip" ||
                contentType == "application/x-rar-compressed" ||
                contentType == "application/vnd.android.package-archive" ||
                contentType.startsWith("text/") ||
                contentType.startsWith("application/") -> {
            DocumentMediaItem(
                payload = payload,
                modifier = baseModifier,
                onDownloadClick = { onClick?.invoke() },
                onLongPress = { onLongPress?.invoke(Offset.Zero) },
                isDownloading = isDownloading
            )
        }

        else -> {
            // Unsupported media type
            println("Unsupported media type: $contentType")
            MediaPlaceholder(
                emoji = "❓",
                label = "Unknown",
                modifier = baseModifier,
            )
        }
    }
}

@Composable
private fun VideoPreloadEffect(
    data: VideoPlayerData,
    onPreloading: (Boolean) -> Unit,
    onProgress: (Float) -> Unit,
) {
    val preloadService = koinInject<VideoPreloadService>()
    val preloader = koinInject<VideoPreloader>()
    // Kick off the download on an app-scoped coroutine so it survives the chat list leaving
    // composition when the user taps into a video. Without this, the tap cancels the in-flight
    // fetch and the full-screen surface has to restart from byte 0.
    LaunchedEffect(data.fileId, data.payloadKey) {
        preloadService.requestPreload(data)
    }
    // Observe progress for the thumbnail's indicator. This collector is composition-scoped —
    // when the thumbnail goes off-screen the UI stops updating, but the background download
    // continues via the service.
    LaunchedEffect(data.fileId, data.payloadKey) {
        preloader.progressFlow(data.fileId, data.payloadKey).collect { p ->
            onProgress(p)
            onPreloading(p < 1f)
        }
    }
}

/** Placeholder component for unsupported media types. Shows an emoji icon and label. */
@Composable
private fun MediaPlaceholder(
    emoji: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.size(Dimens.MediaBubble.minWidthSolo)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.displayMedium,
        )
    }
}


