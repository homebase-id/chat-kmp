package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.contacts.AvatarCapability
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.client.contacts.AvatarSource
import id.homebase.api.client.contacts.ContactImageRef
import id.homebase.api.client.contacts.payloadKeyHeader
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.resources.MR
import id.homebase.resources.avatar_contact
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The avatar for any identity, as [AvatarResolver] picks it. [onOpenImage] receives whichever image
 * actually rendered and never fires on the initials fallback.
 */
@Composable
fun IdentityAvatar(
    odinId: OdinId,
    options: AvatarOptions,
    modifier: Modifier = Modifier,
    displayName: String? = null,
    capability: AvatarCapability = AvatarCapability.Any,
    onOpenImage: ((SubSamplingImageSource) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val resolver: AvatarResolver = koinInject()
    val flow = remember(resolver, odinId, capability, displayName) {
        resolver.observe(odinId, capability, displayName)
    }
    val source by flow.collectAsStateWithLifecycle(
        initialValue = remember(resolver, odinId, capability, displayName) {
            resolver.resolve(odinId, capability, displayName)
        },
    )

    val stored = source as? AvatarSource.StoredPhoto
    val imageData = remember(stored?.image) { stored?.image?.toImageData() }
    var storedFailed by remember(imageData) { mutableStateOf(false) }

    if (imageData != null && !storedFailed) {
        val onClick = rememberOpenStoredImage(imageData, onOpenImage, options.onClick)
        HomebaseImage(
            imageData = imageData,
            modifier = modifier.size(options.size).clip(CircleShape),
            contentDescription = stringResource(MR.string.avatar_contact),
            contentScale = options.contentScale,
            placeholder = {
                // HomebaseImage already owns the tap; FallbackAvatar would add a second one.
                FallbackAvatar(initials = source.initials, options = options.copy(onClick = null), modifier = Modifier.fillMaxSize())
            },
            // An undecryptable or missing payload steps down to the next candidate, not to a blank circle.
            error = { SideEffect { storedFailed = true } },
            onClick = onClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        )
        return
    }

    PublicAvatar(
        odinId = odinId,
        initials = source.initials,
        options = options,
        modifier = modifier,
        onOpenImage = onOpenImage,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
    )
}

// Remembered: HomebaseImage keys its tap detector on this lambda, and HomebaseImageData is not stable.
@Composable
fun rememberOpenStoredImage(
    imageData: HomebaseImageData,
    onOpenImage: ((SubSamplingImageSource) -> Unit)?,
    fallback: (() -> Unit)?,
): (() -> Unit)? = remember(imageData, onOpenImage, fallback) {
    onOpenImage?.let { open -> { open(SubSamplingImageSource.Remote(imageData.copy(loadFullPayload = true))) } } ?: fallback
}

fun ContactImageRef.toImageData(): HomebaseImageData? {
    val keyHeader = payloadKeyHeader() ?: return null
    return HomebaseImageData.from(
        driveId = driveId,
        fileId = fileId,
        descriptor = payload,
        previewThumbnail = previewThumbnail,
        isEncrypted = isEncrypted,
        keyHeader = keyHeader,
    )
}
