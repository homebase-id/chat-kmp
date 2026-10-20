@file:OptIn(ExperimentalEncodingApi::class)

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
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.contacts.AvatarCapability
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.client.contacts.AvatarSource
import id.homebase.api.client.contacts.ContactImageRef
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.resources.MR
import id.homebase.resources.avatar_contact
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The avatar for any identity. [AvatarResolver] picks the picture so every surface shows the same
 * one; a surface only declares what it can render via [capability]. Pass [displayName] when the
 * surface knows a name for an identity that isn't a saved contact; the initials are derived here.
 *
 * [onOpenImage] receives the source of whichever image actually rendered, so a caller can open it
 * full screen; it never fires on the initials fallback.
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
        // Remembered: HomebaseImage keys its tap detector on this lambda, and HomebaseImageData is
        // not a stable type, so a fresh one each recomposition would restart the detector mid-gesture.
        val onClick = remember(imageData, onOpenImage, options.onClick) {
            onOpenImage?.let { open -> { open(SubSamplingImageSource.Remote(imageData.copy(loadFullPayload = true))) } }
                ?: options.onClick
        }
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

    val publicOptions = remember(options, odinId, onOpenImage) {
        if (onOpenImage == null) options
        else options.copy(onClick = { onOpenImage(SubSamplingImageSource.Avatar(odinId)) }, onClickNeedsImage = true)
    }
    PublicAvatar(
        odinId = odinId,
        initials = source.initials,
        options = publicOptions,
        modifier = modifier,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
    )
}

/** The IV is per-payload, the AES key is the file's. Null until the payload is decodable. */
internal fun ContactImageRef.toImageData(): HomebaseImageData? {
    val kh = keyHeader ?: return null
    val iv = payload.iv?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
    return HomebaseImageData.from(
        driveId = driveId,
        fileId = fileId,
        descriptor = payload,
        // The contact file's own preview, not the payload's.
        previewThumbnail = previewThumbnail,
        isEncrypted = isEncrypted,
        keyHeader = KeyHeader(iv = iv, aesKey = kh.aesKey),
    )
}
