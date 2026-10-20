package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.FallbackAvatar
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.image.HomebaseImage
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.resources.MR
import id.homebase.resources.avatar_contact
import org.jetbrains.compose.resources.stringResource

/**
 * Avatar for a contact-book entry. An entry with an identity resolves through [IdentityAvatar] so it
 * shows what every other screen shows for that person; a manual (identity-less) entry can only have
 * its stored photo or initials.
 *
 * [onClick] receives the source of whichever image was actually rendered, so a caller can open it
 * full screen. The initials fallback never calls it.
 *
 * Pass both scopes to morph whichever image branch rendered into the full-screen viewer.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ContactBookAvatar(
    entry: ContactBookEntry,
    size: Dp = 44.dp,
    onClick: ((SubSamplingImageSource) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val options = AvatarOptions(size = size, fontSize = (size.value * 0.4f).sp)

    val odinId = remember(entry.odinId) { entry.odinId?.takeIf { it.isNotBlank() }?.let { runCatching { OdinId(it) }.getOrNull() } }
    if (odinId != null) {
        IdentityAvatar(
            odinId = odinId,
            options = options,
            displayName = entry.displayName,
            onOpenImage = onClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        )
        return
    }

    val imageData = remember(entry.uniqueId, entry.imagePayload?.lastModified) {
        entry.profileImageData()
    }
    if (imageData != null) {
        // Remembered: HomebaseImage keys its tap detector on this lambda, and
        // HomebaseImageData is not a stable type, so a fresh one each recomposition
        // would restart the detector mid-gesture.
        val openPhoto = remember(imageData, onClick) {
            onClick?.let { { it(SubSamplingImageSource.Remote(imageData.copy(loadFullPayload = true))) } }
        }
        HomebaseImage(
            imageData = imageData,
            modifier = Modifier.size(size).clip(CircleShape),
            contentDescription = stringResource(MR.string.avatar_contact),
            contentScale = ContentScale.Crop,
            onClick = openPhoto,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        )
        return
    }
    FallbackAvatar(initials = entry.avatarInitials, options = options)
}
