package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.video.VideoProcessingPhase
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.LocationPreviewDescriptor
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import id.homebase.resources.MR
import id.homebase.resources.cd_upload_complete
import id.homebase.resources.upload_compressing
import id.homebase.resources.upload_done
import id.homebase.resources.upload_encrypting
import id.homebase.resources.upload_finalizing
import id.homebase.resources.upload_preparing
import id.homebase.resources.upload_segmenting
import id.homebase.resources.upload_sending
import id.homebase.resources.upload_uploading
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * Media message component that decides between single item or gallery view.
 *
 * - Single payload: Renders MediaItem with max 50% width, preserving aspect ratio
 * - Multiple payloads: Renders MediaGallery with fixed grid dimensions
 *
 * @param payloads List of payload descriptors to display
 * @param fileId The file ID on the Homebase drive
 * @param driveId The drive ID where the file is stored
 * @param previewThumbnail Optional embedded preview thumbnail
 * @param modifier Modifier for the container
 * @param onMediaClick Callback when a media item is clicked
 * @param onMediaLongPress Callback when a media item is long-pressed
 * @param uploadStatus Current upload status to show progress overlay, or null when not uploading
 */
@Composable
fun MediaMessage(
    payloads: List<PayloadDescriptor>,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    fileId: Uuid,
    driveId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    keyHeader: KeyHeader,
    preserveAspectRatio: Boolean = true,
    modifier: Modifier = Modifier,
    onMediaClick: ((PayloadDescriptor) -> Unit)? = null,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)? = null,
    onRequestDecryptedFile: ((PayloadDescriptor) -> Unit)? = null,
    liveControls: LiveLocationBubbleControls? = null,
    locationHeaderDescriptor: LocationPreviewDescriptor? = null,
    shape: Shape = RoundedCornerShape(
        topStart = Dimens.Message.cornerRadius,
        topEnd = Dimens.Message.cornerRadius
    ),
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    uploadStatus: UploadStatus? = null,
    /** #964: forwarded to [MediaGallery] so a 2+-image album stretches to the bubble width
     *  in the caption path instead of leaving a gap. No effect on single media. */
    fillWidth: Boolean = false,
    /** #1028: true when this message also carries a text caption. A captioned single image
     *  is pinned to Signal's `media_bubble_max_width` (240dp) so the caption never collapses
     *  to a sliver (char-per-line) and the image never leaves a gap beside it. No effect on
     *  stickers, link-preview cards, or galleries. */
    hasCaption: Boolean = false,
) {
    if (payloads.isEmpty()) return

    // A sticker is always a solo, transparent image. Multi-image bundles keep the
    // opaque letterbox fill (a transparent image inside a grid still letterboxes).
    // Remembered on the payload list so the descriptor parse runs once per change
    // rather than on every recomposition.
    val isSticker = remember(payloads) {
        payloads.size == 1 &&
            (payloads[0].descriptorInfo() as? DescriptorContent.ImageFile)?.isSticker == true
    }

    // A link preview is a single auto-generated card (image + title/description). Unlike a photo or
    // video upload its payload is tiny and the card carries text, so the full-bleed dark
    // UploadProgressOverlay scrim is heavy, redundant with the message's own pending tick, and would
    // hide the crisp local image we now render during send. Skip the scrim for it — the inner card
    // still receives isUploading so it renders the local source instead of a failing drive fetch.
    val isLinkPreview = remember(payloads) {
        payloads.size == 1 && payloads[0].key == ChatProtocol.PAYLOAD_KEY_LINKS
    }

    Box(modifier = Modifier.testTag(ChatBubbleTestTags.MEDIA).animateContentSize()) {
        when (payloads.size) {
            1 -> {
                // Stickers drop the opaque surface fill so transparent pixels show the
                // chat surface through; ordinary photos keep it as a loading/letterbox backdrop.
                val widthModifier = if (isSticker) {
                    modifier
                } else {
                    modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                }
                // #1028: keep a captioned image from resolving to a width that either leaves
                // a gap beside it or (in the inline path, which clamps the caption to the media
                // width) collapses the caption to one character per line.
                //  - fillWidth (block-caption path): fill the bubble width and crop, so a
                //    height-capped image reaches the caption edge (galleries do the same).
                //  - other captions: floor the width at Signal's min_width_with_content (240dp)
                //    ONLY for images narrower than that (portrait / square-ish / tall strips),
                //    cropping to the height band. Wider images (landscape / panorama) keep
                //    their natural width — they're already wide enough and must not shrink.
                // Stickers and link-preview cards keep intrinsic sizing.
                val fillsBubble = fillWidth && !isSticker && !isLinkPreview
                val aspect = remember(payloads) {
                    (payloads[0].thumbnails?.lastOrNull() ?: payloads[0].previewThumbnail)?.let { t ->
                        val w = t.pixelWidth
                        val h = t.pixelHeight
                        if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else null
                    }
                }
                // Height-capped natural width falls below the floor only for narrow images.
                val narrowCaptioned = hasCaption && !fillWidth && !isSticker && !isLinkPreview &&
                    aspect != null &&
                    Dimens.MediaBubble.maxHeight.value * aspect <
                    Dimens.MediaBubble.minWidthWithContent.value
                val sizedModifier = when {
                    fillsBubble ->
                        widthModifier.fillMaxWidth().height(Dimens.MediaBubble.maxHeight)
                    narrowCaptioned ->
                        widthModifier.size(
                            width = Dimens.MediaBubble.minWidthWithContent,
                            height = (Dimens.MediaBubble.minWidthWithContent.value / aspect!!).dp
                                .coerceIn(Dimens.MediaBubble.minHeight, Dimens.MediaBubble.maxHeight),
                        )
                    else ->
                        widthModifier.heightIn(
                            min = Dimens.MediaBubble.minHeight,
                            max = Dimens.MediaBubble.maxHeight,
                        )
                }
                MediaItem(
                    payload = payloads[0],
                    fileId = fileId,
                    driveId = driveId,
                    previewThumbnail = previewThumbnail
                        ?: payloads[0].previewThumbnail?.toEmbeddedThumb(),
                    decryptedFiles = decryptedFiles,
                    keyHeader = keyHeader,
                    modifier = sizedModifier,
                    imageSize = ImageSize.THUMB_MEDIUM,
                    preserveAspectRatio = if (fillsBubble || narrowCaptioned) false else preserveAspectRatio,
                    isSticker = isSticker,
                    onClick = { onMediaClick?.invoke(payloads[0]) },
                    onLongPress = { offset -> onMediaLongPress?.invoke(payloads[0], offset) },
                    onRequestDecryptedFile = if (onRequestDecryptedFile != null) {
                        { onRequestDecryptedFile(payloads[0]) }
                    } else {
                        null
                    },
                    shape = shape,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isDownloading = downloadingFiles.contains("${messageId}_${payloads[0].key}"),
                    messageId = messageId,
                    isUploading = uploadStatus != null,
                    liveControls = liveControls,
                    locationHeaderDescriptor = locationHeaderDescriptor,
                )
            }

            else -> {
                // Multiple media items - show gallery with fixed dimensions
                MediaGallery(
                    payloads = payloads,
                    fileId = fileId,
                    driveId = driveId,
                    previewThumbnail = previewThumbnail,
                    keyHeader = keyHeader,
                    modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    onMediaClick = onMediaClick,
                    onMediaLongPress = onMediaLongPress,
                    shape = shape,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    messageId = messageId,
                    downloadingFiles = downloadingFiles,
                    isUploading = uploadStatus != null,
                    fillWidth = fillWidth,
                )
            }
        }

        if (uploadStatus != null && uploadStatus.showsMediaOverlay(LocalUploadConnected.current) && !isLinkPreview) {
            UploadProgressOverlay(
                status = uploadStatus,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

internal val LocalUploadConnected = compositionLocalOf { true }

// The dark scrim + big spinner is for real work: local prep (thumbnail/resize/compress/
// encrypt = Preparing), video transcode (Processing), the active transfer (Uploading — %
// then Finalizing), and the brief completion tick (Completed). "Sending" is the durably-
// queued handoff waiting for the network: shown while online, but hidden offline — there
// it never progresses (airplane mode) and would spin forever, so the bottom-right outbox
// indicator represents it instead (#948).
internal fun UploadStatus.showsMediaOverlay(isConnected: Boolean): Boolean = when (this) {
    UploadStatus.Sending -> isConnected
    UploadStatus.Preparing,
    is UploadStatus.Processing,
    is UploadStatus.Uploading,
    UploadStatus.Completed -> true
}

@Composable
internal fun UploadProgressOverlay(status: UploadStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (status) {
                UploadStatus.Preparing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Text(
                        text = stringResource(MR.string.upload_preparing),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                UploadStatus.Sending -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Text(
                        text = stringResource(MR.string.upload_sending),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                is UploadStatus.Processing -> {
                    if (status.progress > 0f) {
                        val progressText = "${(status.progress * 100).toInt()}%"
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(40.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                            Text(
                                text = progressText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                    }
                    Text(
                        text = when (status.phase) {
                            VideoProcessingPhase.THUMBNAIL -> stringResource(MR.string.upload_preparing)
                            VideoProcessingPhase.COMPRESSING -> stringResource(MR.string.upload_compressing)
                            VideoProcessingPhase.SEGMENTING -> stringResource(MR.string.upload_segmenting)
                            VideoProcessingPhase.ENCRYPTING -> stringResource(MR.string.upload_encrypting)
                            VideoProcessingPhase.COMPLETE -> stringResource(MR.string.upload_done)
                        },
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                is UploadStatus.Uploading -> {
                    if (status.progress >= 1f) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                        Text(
                            text = stringResource(MR.string.upload_finalizing),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        val progressText = "${(status.progress * 100).toInt()}%"
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(40.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                            Text(
                                text = progressText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            text = stringResource(MR.string.upload_uploading),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                UploadStatus.Completed -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(MR.string.cd_upload_complete),
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(MR.string.upload_done),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
