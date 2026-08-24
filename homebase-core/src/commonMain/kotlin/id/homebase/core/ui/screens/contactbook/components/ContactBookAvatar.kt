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
import id.homebase.api.common.publicImageUrl
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.avatars.FallbackAvatar
import id.homebase.core.image.HomebaseImage
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.resources.MR
import id.homebase.resources.avatar_contact
import org.jetbrains.compose.resources.stringResource

/**
 * Avatar for a contact-book entry, in priority order:
 *  1. An uploaded photo stored on the contact drive ([ContactBookEntry.profileImageData]).
 *  2. An identity contact's published public avatar ([ContactAvatar]).
 *  3. Coloured initials ([FallbackAvatar]).
 *
 * [onClick] receives the source of whichever image was actually rendered, so a caller can open it
 * full screen without re-deriving that priority. The initials fallback never calls it, and the
 * public avatar only does once Coil has the image.
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

    val odinId = entry.odinId
    if (!odinId.isNullOrBlank()) {
        val parsed = remember(odinId) { runCatching { OdinId(odinId) }.getOrNull() }
        if (parsed != null) {
            ContactAvatar(
                odinId = parsed,
                profileImageData = null,
                initials = entry.avatarInitials,
                options = options.copy(
                    onClick = onClick?.let {
                        { it(SubSamplingImageSource.Url(parsed.publicImageUrl())) }
                    },
                    onClickNeedsImage = true,
                ),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
            return
        }
    }
    FallbackAvatar(initials = entry.avatarInitials, options = options)
}
