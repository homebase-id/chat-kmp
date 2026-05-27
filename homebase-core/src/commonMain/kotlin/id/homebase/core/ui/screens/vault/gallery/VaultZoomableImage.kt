@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.media.subsample.ZoomableSubSamplingImage
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.resources.MR
import id.homebase.resources.vault_error_image_unavailable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

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
                PendingOverlay(onTap = onToggleUI)
            }
        }
    } else if (isPending) {
        PendingOverlay(onTap = onToggleUI)
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
            UnavailablePlaceholder(
                contentType = descriptor.contentType ?: "",
                onTap = onToggleUI,
            )
        }
    }
}

@Composable
private fun PendingOverlay(onTap: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { onTap() })
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.inversePrimary,
            )
        }
    }
}

@Composable
private fun UnavailablePlaceholder(contentType: String, onTap: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { onTap() })
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = fileTypeIcon(contentType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = stringResource(MR.string.vault_error_image_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}
