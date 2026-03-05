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
import id.homebase.api.client.link.LinkPreview
import id.homebase.core.ui.theme.Dimens
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * A card-style preview for web links.
 *
 * Displays an image, title, description, and the domain name of the link. When clicked, it opens
 * the URL in the system browser.
 *
 * @param linkPreview The link preview data to display.
 * @param modifier Modifier for the composable.
 * @param isCompact If true, renders side-by-side layout suitable for input bars.
 * @param onCancel Callback when cancel button is clicked (only visible if onCancel is not null).
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

    val domain = try {
        val uri = linkPreview.url.lowercase()
        val host = uri.substringAfter("://").substringBefore("/")
        host.removePrefix("www.")
    } catch (e: Exception) {
        linkPreview.url
    }

    if (isCompact) {
        Row(
            modifier = modifier.fillMaxWidth().background(
                MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)
            ).clip(RoundedCornerShape(12.dp)).clickable { uriHandler.openUri(linkPreview.url) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                if (linkPreview.title.isNotEmpty()) {
                    Text(
                        text = linkPreview.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (linkPreview.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = linkPreview.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                                contentDescription = "Cancel",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            } else if (onCancel != null) {
                IconButton(onClick = onCancel, modifier = Modifier.padding(8.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel")
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
                        ) { Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel") }
                    }
                }

                Column(modifier = Modifier.padding(12.dp)) {
                    if (linkPreview.title.isNotEmpty()) {
                        Text(
                            text = linkPreview.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (linkPreview.description.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = linkPreview.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = domain,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
