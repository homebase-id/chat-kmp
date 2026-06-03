package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.HomebaseConstants
import id.homebase.core.image.decodeBitmap
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.api.client.KeyHeader
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.image.HomebaseImage
import id.homebase.resources.vault_pdf_badge
import id.homebase.resources.vault_upload_failed
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private val CARD_WIDTH = 100.dp
private val CARD_HEIGHT = 120.dp
private val THUMBNAIL_HEIGHT = 88.dp
private val LABEL_HEIGHT = 32.dp
private val CARD_CORNER = 12.dp

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun VaultEntryCard(
    file: VaultEntry,
    sectionTitle: String,
    localAttachmentStore: LocalAttachmentContextStore,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val cardShape = RoundedCornerShape(CARD_CORNER)
    val description = file.label?.ifBlank { null } ?: file.fileName
    val noteTitle = file.noteDisplayTitle

    // Warm the full image on press so the fullscreen viewer opens sharp instead
    // of showing the grid tile upscaled while the payload downloads + decodes.
    val homebaseImageLoader: HomebaseImageLoader = koinInject()
    val prefetchData: HomebaseImageData? = remember(file.fileId, file.payloadDescriptors) {
        val descriptor = file.payloadDescriptors.firstOrNull() ?: return@remember null
        if (descriptor.contentType?.startsWith("image/") != true) return@remember null
        val ivBytes = descriptor.iv?.let {
            try { Base64.decode(it) } catch (_: Exception) { null }
        } ?: return@remember null
        HomebaseImageData(
            driveId = file.driveId,
            fileId = file.fileId,
            payloadKey = descriptor.key,
            loadFullPayload = true,
            lastModified = descriptor.lastModified,
            isEncrypted = file.isEncrypted,
            keyHeader = KeyHeader(iv = ivBytes, aesKey = file.keyHeader.aesKey),
        )
    }

    Column(
        modifier = modifier
            .width(CARD_WIDTH)
            .height(if (!file.label.isNullOrBlank() || file.isNote) CARD_HEIGHT else THUMBNAIL_HEIGHT)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(
                onClickLabel = description,
                onClick = {
                    prefetchData?.let { homebaseImageLoader.prefetchFullPayload(it) }
                    onClick()
                },
            ),
    ) {
        // Thumbnail area — top 88dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(THUMBNAIL_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            // Stacked shadow layers (multi-payload entries like scans, not PDFs)
            if (file.hasMultiplePages && !file.isPdf) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 6.dp, end = 2.dp, top = 4.dp)
                        .graphicsLayer { rotationZ = -2f }
                        .clip(cardShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 2.dp, top = 2.dp)
                        .graphicsLayer { rotationZ = -1f }
                        .clip(cardShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                )
            }

            // Main thumbnail
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val localStore = localAttachmentStore
                val firstPayloadKey = file.payloadDescriptors.firstOrNull()?.key ?: VaultEntry.DEFAULT_PAYLOAD_KEY
                val localCtx = localStore.observe(file.uniqueId, firstPayloadKey)
                    .collectAsStateWithLifecycle(
                        initialValue = localStore.get(file.uniqueId, firstPayloadKey),
                    ).value
                val localImage = localCtx as? LocalAttachmentContext.Image

                VaultCardThumbnail(
                    file = file,
                    localImage = localImage,
                    firstPayloadKey = firstPayloadKey,
                    description = description,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )

                // Upload status overlay
                when (val status = file.uploadStatus) {
                    is VaultUploadStatus.Preparing,
                    is VaultUploadStatus.Uploading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.inversePrimary,
                            )
                        }
                    }

                    is VaultUploadStatus.Failed -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = stringResource(MR.string.vault_upload_failed),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    else -> Unit
                }

                // PDF type badge
                if (file.isPdf && (file.previewThumbnail?.content != null || localImage != null)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = stringResource(MR.string.vault_pdf_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                }

                // Page count badge (multi-payload entries like scans, not PDFs)
                if (file.hasMultiplePages && !file.isPdf) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = file.pageCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
        }

        if (!file.label.isNullOrBlank() || file.isNote) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LABEL_HEIGHT)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = noteTitle ?: file.label ?: file.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun VaultCardThumbnail(
    file: VaultEntry,
    localImage: LocalAttachmentContext.Image?,
    firstPayloadKey: String,
    description: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    // 1. Local file available (pending upload or cached thumbnail)
    if ((file.isImage || file.isPdf) && localImage != null) {
        var imageModifier: Modifier = Modifier.fillMaxSize()
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                imageModifier = imageModifier.sharedBounds(
                    rememberSharedContentState(key = "image-${file.fileId}-${firstPayloadKey}"),
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
            model = localImage.localFilePath,
            contentDescription = description,
            modifier = imageModifier,
            contentScale = ContentScale.Crop,
        )
        return
    }

    // 2. Encrypted server thumbnail (image or PDF — same HomebaseImage path)
    val descriptor = file.payloadDescriptors.firstOrNull()
    val payloadIv = remember(descriptor?.iv) {
        descriptor?.iv?.let {
            try { Base64.decode(it) } catch (_: Exception) { null }
        }
    }
    if ((file.isImage || file.isPdf) && descriptor != null && payloadIv != null) {
        HomebaseImage(
            imageData = HomebaseImageData(
                driveId = file.driveId,
                fileId = file.fileId,
                payloadKey = descriptor.key,
                previewThumbnail = file.previewThumbnail,
                requestedSize = ImageSize.THUMB_MEDIUM,
                isEncrypted = file.isEncrypted,
                keyHeader = KeyHeader(
                    iv = payloadIv,
                    aesKey = file.keyHeader.aesKey,
                ),
                lastModified = descriptor.lastModified,
            ),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            contentDescription = description,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        )
        return
    }

    // 3. PDF embedded preview thumbnail (no encryption key available yet)
    if (file.isPdf && file.previewThumbnail != null) {
        val thumbBitmap = remember(file.previewThumbnail) {
            file.previewThumbnail.decodeBitmap()
        }
        if (thumbBitmap != null) {
            Image(
                bitmap = thumbBitmap,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            return
        }
    }

    // 4. Note preview snippet
    val preview = file.notePreview
    if (preview != null) {
        Text(
            text = preview,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
        return
    }

    // 5. Fallback icon
    Icon(
        imageVector = if (file.isImage) Icons.Outlined.Image else fileTypeIcon(file.contentType),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(32.dp),
    )
}
