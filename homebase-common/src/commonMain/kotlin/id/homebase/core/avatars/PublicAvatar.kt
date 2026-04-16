package id.homebase.core.avatars

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import id.homebase.api.common.OdinId
import id.homebase.resources.MR
import id.homebase.resources.avatar_public
import org.jetbrains.compose.resources.stringResource

@Composable
fun PublicAvatar(
    odinId: OdinId,
    initials: String?,
    options: AvatarOptions,
    modifier: Modifier = Modifier
) {
    val imageUrl = "https://$odinId/pub/image"

    val clickableModifier =
        if (options.onClick != null) {
            modifier
                .size(options.size)
                .clip(CircleShape)
                .clickable { options.onClick.invoke() }
        } else {
            modifier
                .size(options.size)
                .clip(CircleShape)
        }

    SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = stringResource(MR.string.avatar_public),
        contentScale = options.contentScale,
        modifier = clickableModifier
    ) {

        val state by painter.state.collectAsStateWithLifecycle()

        when (state) {
            is AsyncImagePainter.State.Loading,
            is AsyncImagePainter.State.Empty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }

            is AsyncImagePainter.State.Success -> {
                SubcomposeAsyncImageContent()
            }

            is AsyncImagePainter.State.Error -> {
                FallbackAvatar(
                    initials = initials,
                    options = options,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}