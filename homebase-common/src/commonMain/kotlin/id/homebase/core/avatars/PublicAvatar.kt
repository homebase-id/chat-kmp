package id.homebase.core.avatars

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import id.homebase.api.common.OdinId
import id.homebase.api.common.publicImageUrl
import id.homebase.core.HomebaseConstants
import id.homebase.core.media.subsample.imageUrlSharedElementKey
import id.homebase.resources.MR
import id.homebase.resources.avatar_public
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PublicAvatar(
    odinId: OdinId,
    initials: String?,
    options: AvatarOptions,
    modifier: Modifier = Modifier,
    /**
     * Supply both to morph this avatar into (and back out of) the full-screen viewer opened by
     * [options] `onClick`. The key is derived from the published-avatar URL, so it pairs with a
     * [id.homebase.core.media.subsample.SubSamplingImageSource.Url] built from the same identity.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    /**
     * Appended as `?v=<cacheBustKey>` so Coil treats a changed value as a distinct request/cache
     * key instead of serving a stale in-memory image — the URL itself never otherwise changes,
     * so without this a freshly-uploaded photo won't visibly update until the app restarts or
     * Coil's memory cache is evicted for some unrelated reason. Pass e.g.
     * `OwnerSession.profileImageLastModified` for the owner's own avatar; leave null (default)
     * for any other identity, where no such signal exists client-side.
     */
    cacheBustKey: Long? = null,
) {
    val imageUrl = odinId.publicImageUrl().let { url ->
        if (cacheBustKey != null) "$url?v=$cacheBustKey" else url
    }

    // Defense in depth. SingletonImageLoader is also rewired to this
    // instance in AppModule.{android,desktop,native}.kt, so a caller that
    // forgets `imageLoader=` still lands on the configured loader. Passing
    // explicitly here mirrors HomebaseImage.kt:82,146 and survives any
    // future Coil upgrade that changes singleton resolution semantics.
    val imageLoader: ImageLoader = koinInject()

    val containerClick = options.onClick?.takeIf { !options.onClickNeedsImage }
    val imageClick = options.onClick?.takeIf { options.onClickNeedsImage }

    var containerModifier =
        if (containerClick != null) {
            modifier
                .size(options.size)
                .clip(CircleShape)
                .clickable(onClick = containerClick)
        } else {
            modifier
                .size(options.size)
                .clip(CircleShape)
        }

    // Keyed off the plain published URL, never the cache-busted one: the viewer is opened with
    // the plain URL and both ends must agree on the key.
    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            containerModifier = containerModifier.sharedBounds(
                rememberSharedContentState(
                    key = imageUrlSharedElementKey(odinId.publicImageUrl()),
                ),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ ->
                    tween(
                        durationMillis =
                            HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION,
                        easing = FastOutSlowInEasing,
                    )
                },
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
        }
    }

    SubcomposeAsyncImage(
        model = imageUrl,
        imageLoader = imageLoader,
        contentDescription = stringResource(MR.string.avatar_public),
        contentScale = options.contentScale,
        modifier = containerModifier
    ) {

        val state by painter.state.collectAsStateWithLifecycle()

        when (state) {
            is AsyncImagePainter.State.Loading,
            is AsyncImagePainter.State.Empty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }

            is AsyncImagePainter.State.Success -> {
                SubcomposeAsyncImageContent(
                    modifier = if (imageClick != null) {
                        Modifier.fillMaxSize().clickable(onClick = imageClick)
                    } else {
                        Modifier
                    }
                )
            }

            is AsyncImagePainter.State.Error -> {
                FallbackAvatar(
                    initials = initials,
                    // FallbackAvatar applies options.onClick itself; an image-gated
                    // tap must not survive into the no-image branch.
                    options = if (imageClick != null) options.copy(onClick = null) else options,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}