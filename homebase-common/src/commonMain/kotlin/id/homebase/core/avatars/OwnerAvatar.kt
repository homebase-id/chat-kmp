package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.ui.theme.ExtendedColors
import id.homebase.core.util.ifTrue

@Composable
fun OwnerAvatar(
    odinId: OdinId,
    profileImageData: HomebaseImageData?,
    initials: String?,
    driveIsConnected: Boolean? = null,
    driveIsSyncing: Boolean? = null,
    options: AvatarOptions,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
) {
    Box(
        modifier = modifier
            .ifTrue(driveIsConnected != null) { Modifier.size(options.size + 6.dp) }
    ) {
        if (profileImageData != null) {
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
                contentDescription = "Owner Avatar",
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
                modifier = modifier
            )
        }
        if (driveIsConnected != null) {
            if (driveIsSyncing == true) {
                CircularProgressIndicator(
                    modifier = modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp),
                    color = if (driveIsConnected) ExtendedColors.Success else Color.Red,
                    strokeWidth = 4.dp
                )
            } else {
                Box(
                    modifier = modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (driveIsConnected) ExtendedColors.Success else Color.Red)
                        .border(
                            width = 1.dp,
                            color = Color.Black,
                            shape = CircleShape
                        )
                )
            }
        }
    }
}
