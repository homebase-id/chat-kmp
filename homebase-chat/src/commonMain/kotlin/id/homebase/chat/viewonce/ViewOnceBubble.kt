package id.homebase.chat.viewonce

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import id.homebase.core.widget.MorphShape
import id.homebase.resources.MR
import id.homebase.resources.cd_view_once_open
import id.homebase.resources.chat_view_once_expired
import id.homebase.resources.chat_view_once_failed
import id.homebase.resources.chat_view_once_open_on_phone
import id.homebase.resources.chat_view_once_opened
import id.homebase.resources.chat_view_once_opened_by
import id.homebase.resources.chat_view_once_opening
import id.homebase.resources.chat_view_once_photo
import id.homebase.resources.chat_view_once_sent
import id.homebase.resources.chat_view_once_tap_to_view
import id.homebase.resources.chat_view_once_unparseable
import id.homebase.resources.chat_view_once_video
import id.homebase.resources.chat_view_once_unavailable
import id.homebase.resources.chat_view_once_update_to_open
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

enum class ViewOnceOpenPhase { Idle, Opening, Failed }

private enum class Badge { Unparseable, Ready, Unavailable, Sent, Opening, Failed, Opened, Expired }

/** Placeholder only: it never reads the message payload. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ViewOnceBubble(
    descriptor: ViewOnceDescriptor?,
    isOutgoing: Boolean,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    state: ViewOnceState = if (isOutgoing) ViewOnceState.Sent else ViewOnceState.Unopened,
    openedCount: Int = 0,
    openOnPhone: Boolean = false,
    phase: ViewOnceOpenPhase = ViewOnceOpenPhase.Idle,
    onOpen: (() -> Unit)? = null,
    authorName: String? = null,
    authorColor: Color? = null,
    footer: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val consumed = state == ViewOnceState.Opened || state == ViewOnceState.Expired
    // The sender never opens its own copy, and a consumed or desktop copy has nothing left to open.
    val canOpen = onOpen != null && descriptor != null && !isOutgoing && !consumed && !openOnPhone
    // A consumed item renders from state alone: its tombstone carries no descriptor.
    val badge = when {
        state == ViewOnceState.Expired -> Badge.Expired
        state == ViewOnceState.Opened -> Badge.Opened
        descriptor == null -> Badge.Unparseable
        isOutgoing -> Badge.Sent
        canOpen && phase == ViewOnceOpenPhase.Opening -> Badge.Opening
        canOpen && phase == ViewOnceOpenPhase.Failed -> Badge.Failed
        canOpen -> Badge.Ready
        else -> Badge.Unavailable
    }
    // On a sent bubble only the glyph dims; text stays at full contentColor so it keeps its contrast.
    val mutedColor = if (isOutgoing) contentColor else colors.onSurfaceVariant
    val kindLabel = descriptor?.let {
        stringResource(if (it.kind == ViewOnceDescriptor.KIND_VIDEO) MR.string.chat_view_once_video else MR.string.chat_view_once_photo)
    }
    val text = when {
        consumed -> BubbleText(
            title = when {
                state == ViewOnceState.Expired -> stringResource(MR.string.chat_view_once_expired)
                isOutgoing && openedCount > 1 -> pluralStringResource(MR.plurals.chat_view_once_opened_by, openedCount, openedCount)
                else -> stringResource(MR.string.chat_view_once_opened)
            },
            titleColor = mutedColor,
            subtitle = kindLabel,
            subtitleColor = mutedColor,
        )
        descriptor == null -> BubbleText(
            title = stringResource(MR.string.chat_view_once_unparseable),
            titleColor = contentColor,
            subtitle = stringResource(MR.string.chat_view_once_update_to_open),
            subtitleColor = mutedColor,
        )
        else -> BubbleText(
            title = kindLabel.orEmpty(),
            titleColor = contentColor,
            subtitle = when (badge) {
                Badge.Opening -> stringResource(MR.string.chat_view_once_opening)
                Badge.Failed -> stringResource(MR.string.chat_view_once_failed)
                Badge.Ready -> stringResource(MR.string.chat_view_once_tap_to_view)
                Badge.Sent -> stringResource(MR.string.chat_view_once_sent)
                else -> stringResource(if (openOnPhone) MR.string.chat_view_once_open_on_phone else MR.string.chat_view_once_unavailable)
            },
            subtitleColor = when (badge) {
                Badge.Failed -> colors.error
                Badge.Ready -> colors.primary
                else -> mutedColor
            },
            subtitleEmphasised = badge == Badge.Ready,
            phoneIcon = badge == Badge.Unavailable && openOnPhone,
        )
    }

    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec())
    val pressMorph by animateFloatAsState(if (pressed) 1f else 0f, motion.fastSpatialSpec())
    val openLabel = stringResource(MR.string.cd_view_once_open)

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape)
            .background(containerColor)
            .then(
                if (canOpen && phase != ViewOnceOpenPhase.Opening) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = openLabel,
                        role = Role.Button,
                        onClick = onOpen!!,
                    )
                } else Modifier
            )
            .widthIn(min = 208.dp)
            .semantics(mergeDescendants = true) {}
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        if (authorName != null) {
            Text(
                text = authorName,
                style = MaterialTheme.typography.labelLarge.withContentDirection(),
                color = authorColor ?: contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
        FooterTrailingOrBelow(footer = footer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = badge,
                    transitionSpec = {
                        (scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f) + fadeIn(HANDOFF_IN))
                            .togetherWith(scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f) + fadeOut(HANDOFF_OUT))
                    },
                    contentAlignment = Alignment.Center,
                ) { target ->
                    ViewOnceBadge(
                        badge = target,
                        isOutgoing = isOutgoing,
                        pressMorph = pressMorph,
                        contentColor = contentColor,
                        containerColor = containerColor,
                    )
                }
                // Title and subtitle change as one unit, so a frame never pairs the new title with the old subtitle.
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        (slideInVertically(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(HANDOFF_IN))
                            .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 3 } + fadeOut(HANDOFF_OUT))
                            .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
                    },
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                ) { shown -> BubbleTextBlock(shown) }
            }
        }
    }
}

private data class BubbleText(
    val title: String,
    val titleColor: Color,
    val subtitle: String?,
    val subtitleColor: Color,
    val subtitleEmphasised: Boolean = false,
    val phoneIcon: Boolean = false,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BubbleTextBlock(text: BubbleText) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = text.title,
            style = MaterialTheme.typography.titleMediumEmphasized.withContentDirection(),
            color = text.titleColor,
        )
        if (text.subtitle != null) {
            val textStyle = if (text.subtitleEmphasised) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
            val lineHeight = with(LocalDensity.current) { textStyle.lineHeight.toDp() }
            Row(verticalAlignment = Alignment.Top) {
                if (text.phoneIcon) {
                    // Sized to one line so a wrapped label keeps the icon beside its first line.
                    Box(Modifier.padding(end = 4.dp).height(lineHeight), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            tint = text.subtitleColor,
                            modifier = Modifier.size(lineHeight * 0.8f),
                        )
                    }
                }
                Text(text = text.subtitle, style = textStyle.withContentDirection(), color = text.subtitleColor)
            }
        }
    }
}

// An English string inside an RTL layout keeps its own punctuation and ellipsis on the right side.
private fun TextStyle.withContentDirection() = copy(textDirection = TextDirection.Content)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewOnceBadge(badge: Badge, isOutgoing: Boolean, pressMorph: Float, contentColor: Color, containerColor: Color) {
    val colors = MaterialTheme.colorScheme
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Circle) }
    val shape = MorphShape(morph, pressMorph)
    val badgeModifier = Modifier.size(BADGE_SIZE)
    // Final states keep the cookie's silhouette but empty it, so they can't be mistaken for one still waiting.
    val finalRing = if (isOutgoing) contentColor.copy(alpha = 0.8f) else colors.outline
    val finalGlyph = if (isOutgoing) contentColor.copy(alpha = 0.78f) else colors.onSurfaceVariant
    when (badge) {
        Badge.Ready -> {
            val invite = remember { Animatable(0.72f) }
            val spring = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
            LaunchedEffect(Unit) { invite.animateTo(1f, spring) }
            FilledBadge(badgeModifier.graphicsLayer { scaleX = invite.value; scaleY = invite.value; rotationZ = (1f - invite.value) * -120f }, shape, colors.primary) {
                DigitIcon(ViewOnceDigitIcon, colors.onPrimary)
            }
        }
        // Inverse of the bubble, so the sender's badge reads as solid rather than a faded copy of the recipient's.
        Badge.Sent -> FilledBadge(badgeModifier, shape, contentColor) {
            DigitIcon(ViewOnceDigitIcon, containerColor)
        }
        Badge.Unavailable -> RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), colors.outline) {
            DigitIcon(ViewOnceDigitIcon, colors.onSurfaceVariant)
        }
        Badge.Opening -> ViewOnceLoadingIndicator(badgeModifier)
        Badge.Failed -> RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), colors.error) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.error, modifier = Modifier.size(22.dp))
        }
        Badge.Opened -> RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), finalRing) {
            Icon(Icons.Default.Check, contentDescription = null, tint = finalGlyph, modifier = Modifier.size(22.dp))
        }
        Badge.Expired -> RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), finalRing) {
            Icon(Icons.Outlined.TimerOff, contentDescription = null, tint = finalGlyph, modifier = Modifier.size(20.dp))
        }
        Badge.Unparseable -> RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), colors.outline) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

/** The bubble and the viewer share it, so "opening" looks the same in both places. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ViewOnceLoadingIndicator(modifier: Modifier = Modifier) {
    ContainedLoadingIndicator(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        indicatorColor = MaterialTheme.colorScheme.primary,
        containerShape = MaterialShapes.Cookie9Sided.toShape(),
    )
}

@Composable
private fun FilledBadge(modifier: Modifier, shape: Shape, color: Color, content: @Composable () -> Unit) {
    Box(modifier.clip(shape).background(color), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun RingBadge(modifier: Modifier, shape: Shape, ringColor: Color, content: @Composable () -> Unit) {
    Box(modifier.border(1.5.dp, ringColor, shape), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun DigitIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
}

private val BADGE_SIZE = 44.dp

// Out, then in: the outgoing state is gone before the incoming one shows, so no frame reads as two overlapping labels.
private const val HANDOFF_OUT_MS = 90
private val HANDOFF_OUT = tween<Float>(HANDOFF_OUT_MS, easing = FastOutLinearInEasing)
private val HANDOFF_IN = tween<Float>(180, delayMillis = HANDOFF_OUT_MS, easing = LinearOutSlowInEasing)

// The timestamp tucks beside the content like a text bubble's, and drops below when that would squeeze it.
@Composable
private fun FooterTrailingOrBelow(footer: @Composable () -> Unit, content: @Composable () -> Unit) {
    Layout(contents = listOf(content, footer)) { (contentMeasurables, footerMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val footerPlaceable = footerMeasurables.firstOrNull()?.measure(loose)
        val footerWidth = footerPlaceable?.width ?: 0
        val footerHeight = footerPlaceable?.height ?: 0
        val gap = if (footerPlaceable == null) 0 else 12.dp.roundToPx()
        val body = contentMeasurables.first()
        val inlineMax = (constraints.maxWidth - footerWidth - gap).coerceAtLeast(0)
        val inline = !constraints.hasBoundedWidth || body.maxIntrinsicWidth(constraints.maxHeight) <= inlineMax
        val bodyPlaceable = body.measure(
            if (inline && constraints.hasBoundedWidth) loose.copy(maxWidth = inlineMax) else loose,
        )
        val width = if (inline) bodyPlaceable.width + gap + footerWidth else maxOf(bodyPlaceable.width, footerWidth)
        val layoutWidth = width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = if (inline) maxOf(bodyPlaceable.height, footerHeight) else bodyPlaceable.height + footerHeight
        layout(layoutWidth, height) {
            bodyPlaceable.placeRelative(0, if (inline) (height - bodyPlaceable.height) / 2 else 0)
            footerPlaceable?.placeRelative(layoutWidth - footerWidth, height - footerHeight)
        }
    }
}
