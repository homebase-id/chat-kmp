@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.vault.gallery

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.HomebaseConstants
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.media.ZoomState
import id.homebase.core.media.ZoomableContainer
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

    val pageImageData = remember(file.fileId, descriptor.key, descriptor.iv, descriptor.lastModified) {
        val payloadIv = descriptor.iv?.let {
            try {
                Base64.decode(it)
            } catch (_: Exception) {
                null
            }
        } ?: return@remember null
        HomebaseImageData(
            driveId = file.driveId,
            fileId = file.fileId,
            payloadKey = descriptor.key,
            previewThumbnail = file.previewThumbnail,
            loadFullPayload = true,
            lastModified = descriptor.lastModified,
            isEncrypted = file.isEncrypted,
            keyHeader = KeyHeader(iv = payloadIv, aesKey = file.keyHeader.aesKey),
        )
    }

    val isPending = descriptor.iv == null
    val zoomState = remember(descriptor.key) { ZoomState() }
    if (localFilePath != null) {
        ZoomableContainer(state = zoomState, onTap = onToggleUI) {
            Box(modifier = Modifier.fillMaxSize()) {
                var imageModifier: Modifier = Modifier.fillMaxSize()
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        imageModifier = imageModifier.sharedBounds(
                            rememberSharedContentState(key = "image-${file.fileId}-${descriptor.key}"),
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
                    model = localFilePath,
                    contentDescription = file.label?.ifBlank { null } ?: file.fileName,
                    modifier = imageModifier,
                    contentScale = ContentScale.Fit,
                )
                if (isPending) {
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
        }
    } else if (pageImageData != null) {
        ZoomableContainer(state = zoomState, onTap = onToggleUI) {
            HomebaseImage(
                imageData = pageImageData,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                contentDescription = file.label?.ifBlank { null } ?: file.fileName,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleUI() })
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = fileTypeIcon(descriptor.contentType ?: ""),
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
}
