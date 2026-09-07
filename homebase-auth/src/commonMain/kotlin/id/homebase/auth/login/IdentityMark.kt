package id.homebase.auth.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.FallbackAvatar
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.HomebaseMark
import id.homebase.core.ui.theme.HomebaseBrand
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.homebase_logo
import org.jetbrains.compose.resources.stringResource

/** A resolved profile is the only thing that earns the avatar; everything else keeps the mark. */
internal val IdentityPreview.isResolved: Boolean get() = displayName != null

internal fun IdentityPreview.label(): String = displayName ?: odinId.domainName

@Composable
internal fun IdentityMark(
    identity: IdentityPreview?,
    size: Dp = 72.dp,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    // Only the avatar takes it: a circular ring around the squircle mark would fight its shape.
    avatarRing: Color = Color.Unspecified,
) {
    AnimatedContent(
        targetState = identity?.takeIf { it.isResolved },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier,
    ) { resolved ->
        if (resolved == null) {
            BrandGlyph(size = size)
        } else {
            IdentityAvatar(
                identity = resolved,
                size = size,
                containerColor = containerColor,
                contentColor = contentColor,
                modifier = if (avatarRing.isSpecified) {
                    Modifier.border(2.dp, avatarRing, CircleShape)
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * The bare 'h' filled with the app icon's top-left-purple to bottom-right-blue ramp. `Icon` only
 * takes a flat tint, so the glyph's own alpha is the mask — and `BlendMode.SrcIn` needs the
 * offscreen layer, or it composites against the whole window and paints a solid rectangle.
 */
@Composable
private fun BrandGlyph(size: Dp) {
    val onDark = MaterialTheme.colorScheme.surfaceContainerLowest.luminance() < 0.5f
    val ramp = if (onDark) {
        listOf(HomebaseBrand.PurpleOnDark, HomebaseBrand.BlueOnDark)
    } else {
        listOf(HomebaseBrand.Purple, HomebaseBrand.Blue)
    }
    Image(
        imageVector = HomebaseIcons.HomebaseMark,
        contentDescription = stringResource(MR.string.homebase_logo),
        modifier = Modifier
            .size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.linearGradient(
                        colors = ramp,
                        start = Offset.Zero,
                        end = Offset(this.size.width, this.size.height),
                    ),
                    blendMode = BlendMode.SrcIn,
                )
            },
    )
}

/**
 * `PublicAvatar` spins while it loads, and a spinner here would read as "we are checking whether
 * this identity exists" — which the public profile can never answer. Unresolved stays on the
 * initials, which already looks finished.
 */
@Composable
internal fun IdentityAvatar(
    identity: IdentityPreview,
    size: Dp,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val options = AvatarOptions(
        size = size,
        containerColor = containerColor,
        contentColor = contentColor,
    )
    val initials = identity.label().initials()
    if (identity.isResolved) {
        PublicAvatar(
            odinId = identity.odinId,
            initials = initials,
            options = options,
            modifier = modifier,
        )
    } else {
        FallbackAvatar(initials = initials, options = options, modifier = modifier)
    }
}
