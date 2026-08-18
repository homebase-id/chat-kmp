package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.resources.MR
import id.homebase.resources.avatar_owner
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactAvatar(
    odinId: OdinId,
    profileImageData: HomebaseImageData?,
    initials: String?,
    options: AvatarOptions,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope?= null,
    animatedVisibilityScope: AnimatedVisibilityScope?= null,
    /** Forwarded to [PublicAvatar] when [profileImageData] is null — see its doc. */
    cacheBustKey: Long? = null,
) {
    if (profileImageData != null) {
        if (animatedVisibilityScope == null) {
            throw IllegalArgumentException("animatedVisibilityScope cannot be null when profile image specified")
        }

        HomebaseImage(
            imageData = profileImageData,
            modifier = modifier
                .size(options.size)
                .clip(CircleShape)
                .let {
                    if (options.onClick != null) {
                        it.clickable { options.onClick.invoke() }
                    } else it
                },
            contentDescription = stringResource(MR.string.avatar_owner),
            contentScale = options.contentScale,
            onClick = options.onClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )
    } else {
        PublicAvatar(
            odinId = odinId,
            initials = initials,
            options = options,
            modifier = modifier,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            cacheBustKey = cacheBustKey,
        )
    }
}
