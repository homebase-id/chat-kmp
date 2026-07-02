package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import coil3.compose.AsyncImage
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
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
import id.homebase.chat.services.builder.LocationPreviewDescriptor
import id.homebase.chat.widget.DocumentMediaItem
import id.homebase.chat.widget.LinkPreviewCard
import id.homebase.chat.widget.LocationPreviewCard
import id.homebase.core.HomebaseConstants
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.thumbSizesFrom
import id.homebase.core.image.ImageSize
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.media.subsample.ZoomableSubSamplingImage
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.widget.AudioPlayerWidget
import id.homebase.resources.MR
import id.homebase.resources.chat_message_image_attachment
import id.homebase.resources.chat_message_video_thumbnail
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Moments-specific clone of `id.homebase.chat.widget.MediaItem`.
 *
 * Renders a single media item based on the payload's content type. This file was forked from
 * the chat widget so it can diverge to fit Moments' UX without disturbing chat.
 */
@Composable
fun MomentMediaItem(
    payload: PayloadDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String> = persistentMapOf(),
    modifier: Modifier = Modifier,
    keyHeader: KeyHeader,
    imageSize: ImageSize? = ImageSize.THUMB_MEDIUM,
    preserveAspectRatio: Boolean = false,
    // Fill the box the parent hands us (Fit, whole image visible) instead of
    // imposing the media's own aspect ratio on the layout. Set when the host
    // gives an explicit size — e.g. the comments-shrink band or the detail/reels
    // pager page — so the image collapses into that box like the video tile does
    // (which fills via plain fillMaxSize). Without this the intrinsic
    // `.aspectRatio()` modifier below lets the image size to its own ratio inside
    // a height-constrained pager and overflow the band.
    fitBounds: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    onRequestDecryptedFile: (() -> Unit)? = null,
    // Render photos through [ZoomableSubSamplingImage] so the user can
    // pinch-zoom/pan/double-tap inline (timeline + reels). Off by default so
    // non-photo contexts and callers that don't want inline zoom keep the
    // lightweight HomebaseImage/AsyncImage path. Only affects image payloads.
    enableZoom: Boolean = false,
    // Reports `true` while the zoomed-in photo is panned past fit and `false`
    // when it returns to fit. The host (carousel pager) uses it to suspend
    // page-swiping so panning a zoomed photo doesn't flip pages.
    onZoomedChanged: ((Boolean) -> Unit)? = null,
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
    // When set, this payload's bytes live on the followed author's drive: read over peer by
    // [globalTransitId] from [driveId] on this identity (a feed post from someone you follow).
    // Both must be set together. Null on the local path (Moments, own posts).
    remoteOdinId: OdinId? = null,
    globalTransitId: Uuid? = null,
) {
    val contentType = payload.contentType ?: ""
    val imageContentScale = if (preserveAspectRatio || fitBounds) ContentScale.Fit else ContentScale.Crop
    val localVideoContextStore = koinInject<LocalAttachmentContextStore>()
    val localContext = if (messageId != null) {
        val ctx by localVideoContextStore.observe(messageId, payload.key)
            .collectAsStateWithLifecycle(initialValue = localVideoContextStore.get(messageId, payload.key))
        ctx
    } else null

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

    val baseModifier = modifier.clip(shape)

    val finalModifier =
        if (preserveAspectRatio && aspectRatio != null && !fitBounds) {
            baseModifier.aspectRatio(aspectRatio)
        } else {
            baseModifier
        }

    when {
        payload.key == ChatProtocol.PAYLOAD_KEY_LINKS -> {
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
                    emoji = "🔗",
                    label = "Link",
                    modifier = baseModifier,
                )
            }
        }

        payload.key == ChatProtocol.PAYLOAD_KEY_LOCATION -> {
            val locationDescriptors = remember(payload.descriptorContent) {
                payload.descriptorContent?.let { content ->
                    try {
                        OdinSystemSerializer.deserialize<List<LocationPreviewDescriptor>>(
                            content
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            val payloadIv = payload.iv?.let { Base64.decode(it) }

            if (payloadIv != null && locationDescriptors != null) {
                LocationPreviewCard(
                    descriptor = locationDescriptors[0],
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
                    emoji = "📍",
                    label = "Location",
                    modifier = baseModifier,
                )
            }
        }

        contentType.startsWith("image/") && enableZoom -> {
            val imageLocalContext = localContext as? LocalAttachmentContext.Image
            val zoomSource = remember(
                imageLocalContext?.localFilePath,
                driveId, fileId, payload.key, payload.lastModified,
            ) {
                val localPath = imageLocalContext?.localFilePath
                if (localPath != null) {
                    SubSamplingImageSource.LocalFile(filePath = localPath)
                } else {
                    // A public (unencrypted) post carries no IV — build the source anyway (encrypted
                    // only when an IV is present). Bailing to null here left public feed images as a
                    // blank Box that never even requested bytes; matches the non-zoom builder below.
                    val payloadIv = payload.iv?.let { Base64.decode(it) }
                    val imageData = HomebaseImageData(
                        driveId = driveId,
                        fileId = fileId,
                        payloadKey = payload.key,
                        previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                            ?: previewThumbnail,
                        requestedSize = imageSize,
                        availableThumbSizes = thumbSizesFrom(payload.thumbnails),
                        lastModified = payload.lastModified,
                        isEncrypted = payloadIv != null,
                        keyHeader = payloadIv
                            ?.let { KeyHeader(iv = it, aesKey = keyHeader.aesKey) }
                            ?: KeyHeader.empty(),
                        remoteOdinId = remoteOdinId,
                        globalTransitId = globalTransitId,
                        // Zoom needs the original payload so panning into a
                        // pinched photo shows real detail, not an upscaled thumb.
                        loadFullPayload = true,
                    )
                    SubSamplingImageSource.Remote(imageData)
                }
            }
            if (zoomSource != null) {
                ZoomableSubSamplingImage(
                    source = zoomSource,
                    modifier = finalModifier,
                    contentDescription = stringResource(MR.string.chat_message_image_attachment),
                    // At base scale the component doesn't consume taps, so this
                    // still opens comments/detail; while zoomed it pans instead.
                    onTap = onClick,
                    onZoomedChanged = onZoomedChanged,
                    // Moments pass null hero aspect (container-level sharedBounds
                    // path, like chat) — see ZoomableSubSamplingImage docs.
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedContentStateKey = "image-${fileId}-${payload.key}",
                )
            } else {
                Box(modifier = finalModifier)
            }
        }

        contentType.startsWith("image/") -> {
            val imageLocalContext = localContext as? LocalAttachmentContext.Image
            if (imageLocalContext != null) {
                var imageModifier = if (onClick != null || onLongPress != null) {
                    finalModifier.pointerInput(onClick, onLongPress) {
                        detectTapGestures(
                            onTap = { onClick?.invoke() },
                            onLongPress = { offset -> onLongPress?.invoke(offset) },
                        )
                    }
                } else {
                    finalModifier
                }
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        imageModifier = imageModifier.sharedBounds(
                            rememberSharedContentState(key = "image-${fileId}-${payload.key}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ ->
                                tween(
                                    durationMillis = HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION,
                                    easing = FastOutSlowInEasing,
                                )
                            },
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                        )
                    }
                }
                AsyncImage(
                    model = imageLocalContext.localFilePath,
                    contentDescription = stringResource(MR.string.chat_message_image_attachment),
                    modifier = imageModifier,
                    contentScale = imageContentScale,
                )
            } else {
                val imageData =
                    remember(driveId, fileId, payload.key, payload.lastModified, imageSize) {
                        // Public feed posts are unencrypted: a plaintext payload carries
                        // no IV. Build a plaintext request (isEncrypted=false, empty
                        // keyHeader) rather than bailing to an empty Box — the server's
                        // `payloadencrypted=false` header makes the loader return the bytes
                        // as-is (keyHeader ignored). Encrypted payloads (chat, moments) still
                        // carry an IV, so they keep the encrypted path unchanged.
                        val payloadIv = payload.iv?.let { Base64.decode(it) }
                        HomebaseImageData(
                            driveId = driveId,
                            fileId = fileId,
                            payloadKey = payload.key,
                            previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                                ?: previewThumbnail,
                            requestedSize = imageSize,
                            availableThumbSizes = thumbSizesFrom(payload.thumbnails),
                            lastModified = payload.lastModified,
                            isEncrypted = payloadIv != null,
                            keyHeader = payloadIv
                                ?.let { KeyHeader(iv = it, aesKey = keyHeader.aesKey) }
                                ?: KeyHeader.empty(),
                            remoteOdinId = remoteOdinId,
                            globalTransitId = globalTransitId,
                        )
                    }

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
                        availableThumbSizes = thumbSizesFrom(payload.thumbnails),
                        lastModified = payload.lastModified,
                        isEncrypted = true,
                        keyHeader = perPayloadKeyHeader,
                        remoteOdinId = remoteOdinId,
                        globalTransitId = globalTransitId,
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
internal fun VideoPreloadEffect(
    data: VideoPlayerData,
    onPreloading: (Boolean) -> Unit,
    onProgress: (Float) -> Unit,
) {
    val preloadService = koinInject<VideoPreloadService>()
    val preloader = koinInject<VideoPreloader>()
    LaunchedEffect(data.fileId, data.payloadKey) {
        preloadService.requestPreload(data)
    }
    LaunchedEffect(data.fileId, data.payloadKey) {
        preloader.progressFlow(data.fileId, data.payloadKey).collect { p ->
            onProgress(p)
            onPreloading(p < 1f)
        }
    }
}

internal fun formatDurationLabel(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

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
