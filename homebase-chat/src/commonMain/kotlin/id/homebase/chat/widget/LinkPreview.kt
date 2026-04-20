package id.homebase.chat.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.link.LinkPreview
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import id.homebase.resources.MR
import id.homebase.resources.cancel
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

// ─── Shared text content ────────────────────────────────────────────────────

@Composable
private fun LinkPreviewTextContent(
    title: String,
    description: String,
    domain: String,
    maxTitleLines: Int = 2,
    maxDescriptionLines: Int = 3,
) {
    if (title.isNotEmpty()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    if (description.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxDescriptionLines,
            overflow = TextOverflow.Ellipsis
        )
    }

    Spacer(modifier = Modifier.height(if (maxTitleLines > 1) 8.dp else 4.dp))

    Text(
        text = domain,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

// ─── Sender-side card (base64 images from LinkPreview) ──────────────────────

/**
 * A card-style preview for web links — sender side.
 *
 * Displays an image decoded from base64, title, description, and domain. When clicked, opens the
 * URL in the system browser.
 *
 * @param linkPreview The link preview data to display.
 * @param modifier Modifier for the composable.
 * @param isCompact If true, renders side-by-side layout suitable for input bars.
 * @param onCancel Callback when cancel button is clicked (only visible if not null).
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalResourceApi::class)
@Composable
fun LinkPreviewCard(
    linkPreview: LinkPreview,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    onCancel: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current

    val imageBitmap = remember(linkPreview.imageUrl) {
        linkPreview.imageUrl?.let {
            try {
                val base64Data = it.substringAfter("base64,")
                Base64.decode(base64Data).decodeToImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    val domain = extractDomain(linkPreview.url)

    if (isCompact) {
        Row(
            modifier = modifier.fillMaxWidth().background(
                MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)
            ).clip(RoundedCornerShape(12.dp)).clickable { uriHandler.openUri(linkPreview.url) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                LinkPreviewTextContent(
                    title = linkPreview.title,
                    description = linkPreview.description,
                    domain = domain,
                    maxTitleLines = 1,
                    maxDescriptionLines = 1,
                )
            }

            if (imageBitmap != null) {
                Box(modifier = Modifier.height(80.dp).aspectRatio(1f)) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                    if (onCancel != null) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(
                                    alpha = 0.7f
                                )
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(MR.string.cancel),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else if (onCancel != null) {
                IconButton(onClick = onCancel, modifier = Modifier.padding(8.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(MR.string.cancel))
                }
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth().clickable { uriHandler.openUri(linkPreview.url) }) {
            Column {
                Box {
                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(
                                RoundedCornerShape(
                                    topStart = Dimens.Message.cornerRadius,
                                    topEnd = Dimens.Message.cornerRadius
                                )
                            ),
                            contentScale = ContentScale.Crop
                        )
                    }
                    if (onCancel != null) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(
                                    alpha = 0.7f
                                )
                            )
                        ) { Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(MR.string.cancel)) }
                    }
                }

                Column(modifier = Modifier.padding(12.dp)) {
                    LinkPreviewTextContent(
                        title = linkPreview.title,
                        description = linkPreview.description,
                        domain = domain,
                    )
                }
            }
        }
    }
}

// ─── Receiver-side card (image from drive via HomebaseImage) ─────────────────

/**
 * A card-style preview for received link previews — receiver side.
 *
 * Renders the image via [HomebaseImage] using the drive file/payload information, with progressive
 * loading from [previewThumbnail].
 *
 * @param descriptor The link preview descriptor metadata.
 * @param fileId Drive file ID for fetching the image payload.
 * @param driveId Drive ID where the file lives.
 * @param payloadKey Payload key (e.g. `PAYLOAD_KEY_LINKS`).
 * @param keyHeader Key header for decryption.
 * @param previewThumbnail Optional embedded tiny thumbnail for instant display.
 * @param modifier Modifier for the composable.
 */
@Composable
fun LinkPreviewCard(
    descriptor: LinkPreviewDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    payloadKey: String,
    keyHeader: KeyHeader,
    previewThumbnail: EmbeddedThumb? = null,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val domain = extractDomain(descriptor.url)

    Column(modifier = modifier.fillMaxWidth().clickable { uriHandler.openUri(descriptor.url) }) {
        if (descriptor.hasImage) {
            val imageData = remember(driveId, fileId, payloadKey) {
                HomebaseImageData(
                    driveId = driveId,
                    fileId = fileId,
                    payloadKey = payloadKey,
                    previewThumbnail = previewThumbnail,
                    requestedSize = ImageSize.THUMB_MEDIUM,
                    isEncrypted = true,
                    keyHeader = keyHeader,
                    loadFullPayload = true,
                )
            }

            HomebaseImage(
                imageData = imageData,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(
                    RoundedCornerShape(
                        topStart = Dimens.Message.cornerRadius, topEnd = Dimens.Message.cornerRadius
                    )
                ),
                contentScale = ContentScale.Fit,
                contentDescription = descriptor.title,

                )
        }

        Column(modifier = Modifier.padding(12.dp)) {
            LinkPreviewTextContent(
                title = descriptor.title,
                description = descriptor.description,
                domain = domain,
            )
        }
    }
}

// ─── Utility ─────────────────────────────────────────────────────────────────

private fun extractDomain(url: String): String {
    return try {
        val uri = url.lowercase()
        val host = uri.substringAfter("://").substringBefore("/")
        host.removePrefix("www.")
    } catch (e: Exception) {
        url
    }
}
