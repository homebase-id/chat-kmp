@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.media.MediaPendingOverlay
import id.homebase.core.media.MediaUnavailablePlaceholder
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.media.subsample.ZoomableSubSamplingImage
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.resources.MR
import id.homebase.resources.vault_error_image_unavailable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.stringResource


@Composable
fun VaultZoomableImage(
    file: VaultEntry,
    descriptor: PayloadDescriptor,
    localAttachmentStore: LocalAttachmentContextStore,
    onToggleUI: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val localImage by localAttachmentStore.observe(file.uniqueId, descriptor.key)
        .collectAsStateWithLifecycle(
            initialValue = localAttachmentStore.get(file.uniqueId, descriptor.key),
        )
    val localFilePath = (localImage as? LocalAttachmentContext.Image)?.localFilePath

    val previewThumbnail = remember(descriptor.previewThumbnail, file.previewThumbnail) {
        descriptor.previewThumbnail?.toEmbeddedThumb() ?: file.previewThumbnail
    }

    val isPending = descriptor.iv == null

    if (localFilePath != null) {
        val source = remember(localFilePath) {
            SubSamplingImageSource.LocalFile(filePath = localFilePath)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            ZoomableSubSamplingImage(
                source = source,
                modifier = Modifier.fillMaxSize(),
                contentDescription = file.label?.ifBlank { null } ?: file.fileName,
                onTap = onToggleUI,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedContentStateKey = "image-${file.fileId}-${descriptor.key}",
            )
            if (isPending) {
                MediaPendingOverlay(onTap = onToggleUI)
            }
        }
    } else if (isPending) {
        MediaPendingOverlay(onTap = onToggleUI)
    } else {
        val remoteSource =
            remember(file.fileId, descriptor.key, descriptor.iv, descriptor.lastModified) {
                val payloadIv = descriptor.iv?.let {
                    try {
                        Base64.decode(it)
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@remember null
                val imageData = HomebaseImageData(
                    driveId = file.driveId,
                    fileId = file.fileId,
                    payloadKey = descriptor.key,
                    previewThumbnail = previewThumbnail,
                    loadFullPayload = true,
                    lastModified = descriptor.lastModified,
                    isEncrypted = file.isEncrypted,
                    keyHeader = KeyHeader(iv = payloadIv, aesKey = file.keyHeader.aesKey),
                )
                SubSamplingImageSource.Remote(imageData)
            }
        if (remoteSource != null) {
            ZoomableSubSamplingImage(
                source = remoteSource,
                modifier = Modifier.fillMaxSize(),
                contentDescription = file.label?.ifBlank { null } ?: file.fileName,
                onTap = onToggleUI,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedContentStateKey = "image-${file.fileId}-${descriptor.key}",
            )
        } else {
            MediaUnavailablePlaceholder(
                message = stringResource(MR.string.vault_error_image_unavailable),
                icon = fileTypeIcon(descriptor.contentType ?: ""),
                onTap = onToggleUI,
            )
        }
    }
}

