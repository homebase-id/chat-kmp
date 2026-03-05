package id.homebase.chat.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.LinkPreview
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
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalResourceApi::class)
@Composable
fun LinkPreviewCard(linkPreview: LinkPreview, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    val imageBitmap = remember(linkPreview.imageUrl) {
        linkPreview.imageUrl?.let {
            try {
                Base64.decode(it).decodeToImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().clickable { uriHandler.openUri(linkPreview.url) }) {
        Column {
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

                val domain = try {
                    val uri = linkPreview.url.lowercase()
                    val host = uri.substringAfter("://").substringBefore("/")
                    host.removePrefix("www.")
                } catch (e: Exception) {
                    linkPreview.url
                }

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
