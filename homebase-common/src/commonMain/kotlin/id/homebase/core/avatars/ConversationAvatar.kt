package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import id.homebase.core.image.HomebaseImage
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.resources.MR
import id.homebase.resources.avatar_conversation
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConversationAvatar(
    avatarModel: ConversationAvatarModel,
    modifier: Modifier = Modifier,
    options: AvatarOptions = AvatarOptions(),
    /** Receives the source of whichever image rendered, to open it full screen; never fires on a fallback. */
    onOpenImage: ((SubSamplingImageSource) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    when (avatarModel.type) {
        ConversationAvatarModel.Type.ConversationImage -> {
            val imageData = avatarModel.imageData ?: return
            val onClick = remember(imageData, onOpenImage, options.onClick) {
                onOpenImage?.let { open -> { open(SubSamplingImageSource.Remote(imageData.copy(loadFullPayload = true))) } }
                    ?: options.onClick
            }
            HomebaseImage(
                imageData = imageData,
                modifier = modifier
                    .size(options.size)
                    .clip(CircleShape),
                contentDescription = stringResource(MR.string.avatar_conversation),
                contentScale = options.contentScale,
                onClick = onClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }

        ConversationAvatarModel.Type.Connection -> {
            avatarModel.odinId?.let {
                IdentityAvatar(
                    odinId = it,
                    options = options,
                    modifier = modifier,
                    onOpenImage = onOpenImage,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        }

        ConversationAvatarModel.Type.Owner -> {
            avatarModel.odinId?.let {
                // The owner model carries no imageData, so OwnerAvatar draws the public avatar.
                val ownerOptions = remember(options, it, onOpenImage) {
                    if (onOpenImage == null) options
                    else options.copy(onClick = { onOpenImage(SubSamplingImageSource.Avatar(it)) }, onClickNeedsImage = true)
                }
                OwnerAvatar(
                    odinId = it,
                    profileImageData = avatarModel.imageData,
                    initials = avatarModel.initials,
                    options = ownerOptions,
                    modifier = modifier,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }

        ConversationAvatarModel.Type.GroupFallback -> {
            GroupAvatar(initials = avatarModel.initials, options = options, modifier = modifier)
        }
    }
}