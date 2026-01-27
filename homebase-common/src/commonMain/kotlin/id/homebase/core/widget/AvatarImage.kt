package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import id.homebase.core.util.ifTrue

@Composable
fun AvatarImage(
    modifier: Modifier = Modifier,
    avatarUrl: String?,
    avatarInitials: String,
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
        if (avatarUrl?.isNotEmpty() == true) {
            SubcomposeAsyncImage(
                model = avatarUrl,
                contentDescription = "$avatarInitials avatar"
            ) {
                val state by painter.state.collectAsState()
                if (state is AsyncImagePainter.State.Success) {
                    SubcomposeAsyncImageContent()
                } else {
                    // Fallback composable on error and loading
                    Text(
                        text = avatarInitials,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
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