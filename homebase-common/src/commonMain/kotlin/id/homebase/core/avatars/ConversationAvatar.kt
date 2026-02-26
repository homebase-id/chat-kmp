package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import id.homebase.core.image.HomebaseImage

@Composable
fun ConversationAvatar(
    avatarModel: ConversationAvatarModel,
    modifier: Modifier = Modifier,
    options: AvatarOptions = AvatarOptions(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    when (avatarModel.type) {
        ConversationAvatarModel.Type.ConversationImage -> {
            val imageData = avatarModel.imageData ?: return
            HomebaseImage(
                imageData = imageData,
                modifier = modifier
                    .size(options.size)
                    .clip(CircleShape),
                contentDescription = "Conversation Avatar",
                contentScale = options.contentScale,
                onClick = options.onClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }

        ConversationAvatarModel.Type.Connection -> {
            avatarModel.odinId?.let {
                PublicAvatar(
                    odinId = it,
                    initials = avatarModel.initials,
                    options = options,
                    modifier = modifier
                )
            }
        }

        ConversationAvatarModel.Type.Owner -> {
            avatarModel.odinId?.let {
                OwnerAvatar(
                    odinId = it,
                    profileImageData = avatarModel.imageData,
                    initials = avatarModel.initials,
                    options = options,
                    modifier = modifier,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }

        ConversationAvatarModel.Type.GroupFallback -> {
            FallbackAvatar(
                initials = avatarModel.initials,
                options = options,
                modifier = modifier,
                imageVector = Icons.Default.People
            )
        }
    }
}