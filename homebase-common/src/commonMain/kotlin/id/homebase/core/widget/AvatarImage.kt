package id.homebase.core.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.image.toImageBitmap
import id.homebase.core.util.ifTrue
import kotlin.io.encoding.Base64

@Composable
fun AvatarImage(
    modifier: Modifier = Modifier,
    avatarUrl: String?,
    avatarInitials: String,
    avatarTiny: EmbeddedThumb? = null,
    isGroup: Boolean = false,
    size: Dp = 48.dp,
    fontSize: TextUnit = 16.sp,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .ifTrue(onClick != null) { Modifier.clickable { onClick?.invoke() } }
        ,
        contentAlignment = Alignment.Center
    ) {
        val imgContent = avatarTiny?.content
        if (imgContent != null) {
            val imageBitmap = remember(imgContent) {
                try {
                    val base64String = imgContent
                        .removePrefix("data:image/png;base64,")
                        .removePrefix("data:image/jpeg;base64,")
                        .removePrefix("data:image/jpg;base64,")

                    val imageBytes = Base64.decode(base64String)
                    imageBytes.toImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }

            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "$avatarInitials avatar",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }


        } else if (avatarUrl?.isNotEmpty() == true) {
            SubcomposeAsyncImage(
                model = avatarUrl,
                contentDescription = "$avatarInitials avatar"
            ) {
                val state by painter.state.collectAsState()
                if (state is AsyncImagePainter.State.Success) {
                    SubcomposeAsyncImageContent()
                } else {
                    // Fallback composable on error and loading
                    if (isGroup) {
                        // Show group icon
                        Icon(Icons.Default.People, contentDescription = null)
                    } else {
                        Text(
                            text = avatarInitials,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else if (isGroup) {
            // Show group icon
            Icon(Icons.Default.People, contentDescription = null)
        } else {
            // Show initials when no avatar URL
            Text(
                text = avatarInitials,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}