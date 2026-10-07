package id.homebase.chat.viewonce

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.sp
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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

enum class ViewOnceOpenPhase { Idle, Opening, Failed }

private enum class Badge { Unparseable, Ready, Sent, Opening, Failed, Consumed }

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
    val badge = when {
        descriptor == null -> Badge.Unparseable
        consumed -> Badge.Consumed
        isOutgoing -> Badge.Sent
        canOpen && phase == ViewOnceOpenPhase.Opening -> Badge.Opening
        canOpen && phase == ViewOnceOpenPhase.Failed -> Badge.Failed
        else -> Badge.Ready
    }
    val mutedColor = if (isOutgoing) contentColor.copy(alpha = 0.78f) else colors.onSurfaceVariant
    val kindLabel = descriptor?.let {
        stringResource(if (it.kind == ViewOnceDescriptor.KIND_VIDEO) MR.string.chat_view_once_video else MR.string.chat_view_once_photo)
    }
    val title: String
    val subtitle: String?
    val subtitleColor: Color
    when {
        descriptor == null -> {
            title = stringResource(MR.string.chat_view_once_unparseable)
            subtitle = null
            subtitleColor = mutedColor
        }
        consumed -> {
            title = when {
                state == ViewOnceState.Expired -> stringResource(MR.string.chat_view_once_expired)
                isOutgoing && openedCount > 1 -> pluralStringResource(MR.plurals.chat_view_once_opened_by, openedCount, openedCount)
                else -> stringResource(MR.string.chat_view_once_opened)
            }
            subtitle = kindLabel
            subtitleColor = mutedColor
        }
        else -> {
            title = kindLabel.orEmpty()
            subtitle = when (badge) {
                Badge.Opening -> stringResource(MR.string.chat_view_once_opening)
                Badge.Failed -> stringResource(MR.string.chat_view_once_failed)
                else -> when {
                    openOnPhone && !isOutgoing -> stringResource(MR.string.chat_view_once_open_on_phone)
                    canOpen -> stringResource(MR.string.chat_view_once_tap_to_view)
                    else -> stringResource(MR.string.chat_view_once_sent)
                }
            }
            subtitleColor = when {
                badge == Badge.Failed -> colors.error
                canOpen && badge == Badge.Ready -> colors.primary
                else -> mutedColor
            }
        }
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
                ViewOnceBadge(
                    badge = badge,
                    pressMorph = pressMorph,
                    contentColor = contentColor,
                    containerColor = containerColor,
                )
                Column(
                    modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = if (descriptor == null) MaterialTheme.typography.bodyMedium.withContentDirection()
                        else MaterialTheme.typography.titleMediumEmphasized.withContentDirection(),
                        color = when {
                            descriptor == null -> colors.onSurfaceVariant
                            consumed -> mutedColor
                            else -> contentColor
                        },
                    )
                    if (subtitle != null) {
                        AnimatedContent(
                            targetState = subtitle to subtitleColor,
                            transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                        ) { (text, color) ->
                            val inlineIcon = with(LocalDensity.current) { 16.sp.toDp() }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (openOnPhone && !isOutgoing && !consumed && descriptor != null) {
                                    Icon(
                                        imageVector = Icons.Outlined.PhoneAndroid,
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.padding(end = 4.dp).size(inlineIcon),
                                    )
                                }
                                Text(
                                    text = text,
                                    style = if (canOpen && badge == Badge.Ready) MaterialTheme.typography.labelLarge.withContentDirection()
                                    else MaterialTheme.typography.bodyMedium.withContentDirection(),
                                    color = color,
                                )
                                if (canOpen && badge == Badge.Ready) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.padding(start = 4.dp).size(inlineIcon),
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

// An English string inside an RTL layout keeps its own punctuation and ellipsis on the right side.
private fun TextStyle.withContentDirection() = copy(textDirection = TextDirection.Content)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewOnceBadge(badge: Badge, pressMorph: Float, contentColor: Color, containerColor: Color) {
    val colors = MaterialTheme.colorScheme
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Circle) }
    val shape = MorphShape(morph, pressMorph)
    val badgeModifier = Modifier.size(BADGE_SIZE)
    when (badge) {
        Badge.Ready -> FilledBadge(badgeModifier, shape, colors.primary) {
            DigitIcon(ViewOnceDigitIcon, colors.onPrimary)
        }
        // Inverse of the bubble, so the sender's badge reads as solid rather than a faded copy of the recipient's.
        Badge.Sent -> FilledBadge(badgeModifier, shape, contentColor) {
            DigitIcon(ViewOnceDigitIcon, containerColor)
        }
        Badge.Opening -> FilledBadge(badgeModifier, shape, colors.primaryContainer) {
            LoadingIndicator(color = colors.onPrimaryContainer, modifier = Modifier.size(30.dp))
        }
        Badge.Failed -> FilledBadge(badgeModifier, CircleShape, colors.errorContainer) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(22.dp))
        }
        Badge.Consumed -> DashedBadge(badgeModifier, ringColor = contentColor.copy(alpha = 0.55f)) {
            DigitIcon(ViewOnceDigitIcon, contentColor.copy(alpha = 0.7f))
        }
        Badge.Unparseable -> {
            val cookie = MaterialShapes.Cookie9Sided.toShape()
            Box(
                modifier = badgeModifier
                    .clip(cookie)
                    .background(colors.surfaceContainerHighest)
                    .border(1.dp, colors.outline, cookie),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ViewOnceIcon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun FilledBadge(modifier: Modifier, shape: Shape, color: Color, content: @Composable () -> Unit) {
    Box(modifier.clip(shape).background(color), contentAlignment = Alignment.Center) { content() }
}

// Hollow and dashed: the same cookie, emptied, so "consumed" reads at a glance before the text does.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DashedBadge(modifier: Modifier, ringColor: Color, content: @Composable () -> Unit) {
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.5.dp.toPx()
            val dash = 4.dp.toPx()
            val inset = stroke / 2f
            val outline = cookie.createOutline(
                size.copy(width = size.width - stroke, height = size.height - stroke),
                layoutDirection,
                this,
            )
            drawContext.transform.translate(inset, inset)
            drawOutline(
                outline = outline,
                color = ringColor,
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.75f))),
            )
            drawContext.transform.translate(-inset, -inset)
        }
        content()
    }
}

@Composable
private fun DigitIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
}

private val BADGE_SIZE = 44.dp

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
