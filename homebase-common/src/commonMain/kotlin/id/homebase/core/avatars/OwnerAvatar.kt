@file:OptIn(ExperimentalEncodingApi::class, ExperimentalResourceApi::class)

package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.ui.theme.ExtendedColors
import id.homebase.core.util.ifTrue
import id.homebase.resources.MR
import id.homebase.resources.avatar_owner
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

@Composable
fun OwnerAvatar(
    odinId: OdinId,
    profileImageData: HomebaseImageData?,
    initials: String?,
    connectionStatus: AppConnectionStatus? = null,
    driveIsSyncing: Boolean? = null,
    hasDriveError: Boolean = false,
    /** Whatever the avatar sits on, so the status badge reads as cut out of it. */
    statusRingColor: Color = MaterialTheme.colorScheme.surface,
    options: AvatarOptions,
    modifier: Modifier = Modifier,
    onOpenImage: ((SubSamplingImageSource) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    // Preview-only owner image (e.g. the note-to-self avatar built from
    // OwnerSession.profileImagePreviewThumbnail): there is no server-fetchable
    // file (fileId == NIL), so decode and paint the embedded base64 preview
    // directly. Routing it through HomebaseImage would 404 on the chat drive and
    // overlay an error triangle. A real fetchable owner image (fileId != NIL)
    // still goes through HomebaseImage below for progressive loading.
    val previewOnlyBitmap = remember(profileImageData?.previewThumbnail, profileImageData?.fileId) {
        val data = profileImageData ?: return@remember null
        if (data.fileId != Uuid.NIL) return@remember null
        data.previewThumbnail?.content?.takeIf { it.bytes.isNotEmpty() }?.let { content ->
            try {
                content.bytes.decodeToImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .ifTrue(connectionStatus != null) { Modifier.size(options.size + 6.dp) }
    ) {
        if (previewOnlyBitmap != null) {
            Image(
                bitmap = previewOnlyBitmap,
                contentDescription = stringResource(MR.string.avatar_owner),
                contentScale = options.contentScale,
                modifier = Modifier
                    .size(options.size)
                    .clip(CircleShape)
                    .let {
                        if (options.onClick != null) {
                            it.clickable { options.onClick.invoke() }
                        } else it
                    },
            )
        } else if (profileImageData != null) {
            HomebaseImage(
                imageData = profileImageData,
                modifier = Modifier
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
                onOpenImage = onOpenImage,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
        if (connectionStatus != null) {
            if (driveIsSyncing == true && connectionStatus == AppConnectionStatus.Connected) {
                SyncArc(options.size)
            }
            OwnerStatusBadge(connectionStatus, hasDriveError, statusRingColor)
        }
    }
}

private val StatusBadgeSize = 14.dp

@Composable
private fun BoxScope.SyncArc(avatarSize: Dp) {
    val rotation = rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
    )
    // A fixed sweep, because the indeterminate indicator shrinks to a sliver at this size.
    CircularProgressIndicator(
        progress = { 0.7f },
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(-AvatarRingOutset, -AvatarRingOutset)
            .size(avatarSize + AvatarRingOutset * 2)
            .graphicsLayer { rotationZ = rotation.value },
        strokeWidth = 2.dp,
        trackColor = Color.Transparent,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
    )
}

private val AvatarRingOutset = 3.dp

// A different shape per state: the warning colours alone can't be told apart at this size.
@Composable
private fun BoxScope.OwnerStatusBadge(status: AppConnectionStatus, hasDriveError: Boolean, ringColor: Color) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(StatusBadgeSize)
            .background(ringColor, CircleShape)
            .padding(1.dp),
    ) {
        val dot = Modifier.fillMaxSize().padding(1.dp)
        when {
            status == AppConnectionStatus.Connected && hasDriveError -> Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = ExtendedColors.Warning,
            )

            status == AppConnectionStatus.Connected -> Box(dot.background(ExtendedColors.Success, CircleShape))
            status == AppConnectionStatus.Connecting -> Box(dot.border(2.dp, ExtendedColors.Warning, CircleShape))
            else -> Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
