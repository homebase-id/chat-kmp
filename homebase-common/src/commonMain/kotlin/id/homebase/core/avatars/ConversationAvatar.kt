package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import id.homebase.core.image.HomebaseImage

@Composable
fun ConversationAvatar(
    avatarModel: ConversationAvatarModel,
    modifier: Modifier = Modifier,
    options: AvatarOptions? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {

    val opts = options ?: AvatarOptions()

    when (avatarModel.type) {

        ConversationAvatarModel.Type.ConversationImage -> {

            val imageData = avatarModel.imageData ?: return
                ?: throw IllegalArgumentException(
                    "animatedVisibilityScope required for ConversationImage"
                )

            HomebaseImage(
                imageData = imageData,
                modifier = modifier
                    .size(opts.size)
                    .clip(CircleShape),
                contentDescription = "Conversation Avatar",
                contentScale = opts.contentScale,
                onClick = opts.onClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }



        ConversationAvatarModel.Type.Connection -> {
            avatarModel.odinId?.let {
                PublicAvatar(
                    odinId = it,
                    initials = avatarModel.initials,
                    options = opts,
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
                    options = opts,
                    modifier = modifier,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }

        ConversationAvatarModel.Type.GroupFallback -> {
            FallbackAvatar(
                initials = avatarModel.initials,
                options = opts,
                modifier = modifier,
                imageVector = Icons.Default.People
            )
        }
    }
}