package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

@Composable
fun RoundedIcon(
    modifier: Modifier = Modifier,
    imageVector: ImageVector,
    size: Dp = 48.dp,
    onClick: () -> Unit = {},
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier.size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable {
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(size / 2)
        )
    }
}

@Composable
fun RoundedIconFromFile(
    modifier: Modifier = Modifier,
    path: String,
    size: Dp = 48.dp,
    onClick: () -> Unit = {},
    contentDescription: String? = null,
) {
    val imageLoader: ImageLoader = koinInject()
    AsyncImage(
        imageLoader = imageLoader,
        model = path,
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable {
                onClick()
            },
        contentScale = ContentScale.Crop
    )
}