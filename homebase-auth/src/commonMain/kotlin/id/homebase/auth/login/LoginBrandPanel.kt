package id.homebase.auth.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.HomebaseMark
import id.homebase.core.ui.theme.HomebaseBrand
import id.homebase.resources.MR
import id.homebase.resources.homebase_logo
import id.homebase.resources.login_brand_tagline
import id.homebase.resources.login_brand_wordmark
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min

internal val BrandPanelPadding = 56.dp
internal val BrandMarkSize = 64.dp
internal val BrandContentMaxWidth = 288.dp

// Half the brand block, plus slack for the taller resolved-identity variant.
internal val BrandBlockHalfHeight = 128.dp

// The pane at the smallest window that gets this layout; without scaling off it the block stays a
// fixed island in a pane that grew twice the size. Height binds too: in a short pane the block runs
// out of room to grow before the ground runs out of width.
private val BrandReferenceWidth = 576.dp
private val BrandReferenceHeight = 640.dp
private const val MaxBrandScale = 1.75f

// The secondary lines have to clear 4.5:1 wherever the diagonal ground puts them; this measures
// 5.94:1 against its bluer end, where 0.72 gave only 4.75:1 once Montserrat moved the baseline.
private val OnBrandMuted = HomebaseBrand.White.copy(alpha = 0.85f)
private val OnBrandAvatar = HomebaseBrand.White.copy(alpha = 0.12f)
private val BrandAvatarRing = HomebaseBrand.White.copy(alpha = 0.85f)

/** Persists across every view state, so drive-sync progress on the right still says who is being
 *  signed in on the left. */
@Composable
internal fun LoginBrandPanel(
    identity: IdentityPreview?,
    // False while the continue card is on offer: that card already carries the domain, and the two
    // sat 700 px apart saying the same thing.
    showDomain: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val scale = min(
            maxWidth / BrandReferenceWidth,
            maxHeight / BrandReferenceHeight,
        ).coerceIn(1f, MaxBrandScale)
        val roomy = scale >= 1.3f
        val padding = BrandPanelPadding * scale
        val contentWidth = BrandContentMaxWidth * scale

        // Brand colour in both themes (manual pp.10-11), so its content is the negative version (p9).
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = HomebaseBrand.Blue,
            contentColor = HomebaseBrand.White,
        ) {
            Box {
                LoginBrandArtwork(
                    modifier = Modifier.matchParentSize(),
                    blockHalfHeight = BrandBlockHalfHeight * scale,
                    blockRight = padding + contentWidth,
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedContent(
                        targetState = identity?.takeIf { it.isResolved },
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                    ) { resolved ->
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier = Modifier.widthIn(max = contentWidth),
                        ) {
                            if (resolved == null) {
                                Icon(
                                    imageVector = HomebaseIcons.HomebaseMark,
                                    contentDescription = stringResource(MR.string.homebase_logo),
                                    tint = HomebaseBrand.White,
                                    modifier = Modifier.size(BrandMarkSize * scale),
                                )
                                Spacer(modifier = Modifier.height(32.dp * scale))
                                Text(
                                    text = stringResource(MR.string.login_brand_wordmark),
                                    style =
                                        if (roomy) MaterialTheme.typography.displayMedium
                                        else MaterialTheme.typography.displaySmall,
                                )
                                Spacer(modifier = Modifier.height(12.dp * scale))
                                Text(
                                    text = stringResource(MR.string.login_brand_tagline),
                                    style =
                                        if (roomy) MaterialTheme.typography.titleLarge
                                        else MaterialTheme.typography.titleMedium,
                                    color = OnBrandMuted,
                                )
                            } else {
                                // p8 sets a 48 px floor for the digital mark.
                                Icon(
                                    imageVector = HomebaseIcons.HomebaseMark,
                                    contentDescription = stringResource(MR.string.homebase_logo),
                                    tint = HomebaseBrand.White,
                                    modifier = Modifier.size(48.dp * scale),
                                )
                                Spacer(modifier = Modifier.height(32.dp * scale))
                                IdentityAvatar(
                                    identity = resolved,
                                    size = 96.dp * scale,
                                    containerColor = OnBrandAvatar,
                                    contentColor = HomebaseBrand.White,
                                    modifier = Modifier.border(2.dp, BrandAvatarRing, CircleShape),
                                )
                                Spacer(modifier = Modifier.height(24.dp * scale))
                                Text(
                                    text = resolved.label(),
                                    style =
                                        if (roomy) MaterialTheme.typography.headlineLarge
                                        else MaterialTheme.typography.headlineMedium,
                                    modifier = Modifier.testTag("brand_identity_name"),
                                )
                                if (showDomain) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = resolved.odinId.domainName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = OnBrandMuted,
                                    )
                                }
                                resolved.status?.let { status ->
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnBrandMuted,
                                        modifier = Modifier.testTag("brand_identity_status"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
