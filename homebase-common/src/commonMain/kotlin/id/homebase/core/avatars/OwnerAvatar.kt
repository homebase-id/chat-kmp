package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
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
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val borderBrush = remember(driveIsConnected) {
        if (driveIsConnected == true) {
            Brush.sweepGradient(
                colors = listOf(
                    Color.Green.copy(alpha = 0.1f),
                    Color.Green.copy(alpha = 0.3f),
                    Color.Green.copy(alpha = 1.0f),
                    Color.Green.copy(alpha = 0.3f),
                    Color.Green.copy(alpha = 0.1f)
                )
            )
        } else {
            Brush.sweepGradient(
                colors = listOf(
                    Color.Red.copy(alpha = 0.1f),
                    Color.Red.copy(alpha = 0.3f),
                    Color.Red.copy(alpha = 1.0f),
                    Color.Red.copy(alpha = 0.3f),
                    Color.Red.copy(alpha = 0.1f)
                )
            )
        }
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .size(options.size)
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
        Box(
            modifier = modifier
                .size(options.size)
                .clip(CircleShape)
                .ifTrue(driveIsConnected != null && driveIsSyncing == true) {
                    Modifier
                        .drawBehind {
                            rotate(rotation) {
                                drawCircle(
                                    brush = borderBrush,
                                    radius = size.minDimension / 2,
                                    style = Stroke(width = 3.dp.toPx())
                                )
                            }
                        }
                }
            .ifTrue(driveIsConnected != null && driveIsSyncing != true) {
                Modifier.border(
                    width = 2.dp,
                    brush = borderBrush,
                    shape = CircleShape
                )
            }
        )
    }
}
