package id.homebase.chat.viewonce

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import id.homebase.resources.cd_view_once_toggle
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.IntSize
import id.homebase.resources.chat_view_once_tap_to_retry
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
import id.homebase.resources.chat_view_once_open_on_phone_body
import id.homebase.resources.chat_view_once_open_on_phone_title
import id.homebase.resources.ok
import id.homebase.resources.chat_view_once_opened
import id.homebase.resources.chat_view_once_screenshot_taken
import id.homebase.resources.chat_view_once_opened_by
import id.homebase.resources.chat_view_once_opening
import id.homebase.resources.chat_view_once_photo
import id.homebase.resources.chat_view_once_sent
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
    screenshotTaken: Boolean = false,
    canView: Boolean = true,
    phase: ViewOnceOpenPhase = ViewOnceOpenPhase.Idle,
    onOpen: (() -> Unit)? = null,
    authorName: String? = null,
    authorColor: Color? = null,
    footer: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val consumed = state == ViewOnceState.Opened || state == ViewOnceState.Expired
    // The sender never opens its own copy, and a consumed or desktop copy has nothing left to open.
    val canOpen = onOpen != null && descriptor != null && !isOutgoing && !consumed && canView
    val openOnPhone = !canView && descriptor != null && !isOutgoing && !consumed
    var explainOnPhone by remember { mutableStateOf(false) }
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
    val mutedColor = if (isOutgoing) contentColor.copy(alpha = MUTED_ALPHA) else colors.onSurfaceVariant
    // A spent label steps back through weight, not fade: on the sent bubble a faded onPrimary reads as disabled.
    val spentColor = if (isOutgoing) contentColor else colors.onSurfaceVariant
    val kindLabel = descriptor?.let {
        stringResource(if (it.kind == ViewOnceDescriptor.KIND_VIDEO) MR.string.chat_view_once_video else MR.string.chat_view_once_photo)
    }
    val shotNote = if (screenshotTaken) stringResource(MR.string.chat_view_once_screenshot_taken) else null
    val text = when {
        consumed -> BubbleText(
            title = when {
                state == ViewOnceState.Expired -> stringResource(MR.string.chat_view_once_expired)
                isOutgoing && openedCount > 1 -> pluralStringResource(MR.plurals.chat_view_once_opened_by, openedCount, openedCount)
                else -> stringResource(MR.string.chat_view_once_opened)
            },
            titleColor = spentColor,
            spent = true,
            note = shotNote,
        )
        descriptor == null -> BubbleText(
            title = stringResource(MR.string.chat_view_once_unparseable),
            titleColor = contentColor,
            status = stringResource(MR.string.chat_view_once_update_to_open),
            statusColor = mutedColor,
        )
        else -> BubbleText(
            title = kindLabel.orEmpty(),
            titleColor = contentColor,
            status = when (badge) {
                Badge.Ready -> null
                Badge.Opening -> stringResource(MR.string.chat_view_once_opening)
                Badge.Failed -> stringResource(MR.string.chat_view_once_failed)
                Badge.Sent -> null
                else -> stringResource(if (openOnPhone) MR.string.chat_view_once_open_on_phone else MR.string.chat_view_once_unavailable)
            },
            statusColor = if (badge == Badge.Failed) colors.error else mutedColor,
            statusInline = badge == Badge.Opening,
            hint = if (badge == Badge.Failed) stringResource(MR.string.chat_view_once_tap_to_retry) else null,
            hintColor = mutedColor,
            note = shotNote,
        )
    }

    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec())
    val pressMorph by animateFloatAsState(if (pressed) 1f else 0f, motion.fastSpatialSpec())
    val openLabel = stringResource(MR.string.cd_view_once_open)
    val viewOnceLabel = stringResource(MR.string.cd_view_once_toggle)
    // The ticks already say "sent" on screen; a screen reader still hears it.
    val sentLabel = if (badge == Badge.Sent) stringResource(MR.string.chat_view_once_sent) else null

    if (explainOnPhone) {
        AlertDialog(
            onDismissRequest = { explainOnPhone = false },
            title = { Text(stringResource(MR.string.chat_view_once_open_on_phone_title)) },
            text = { Text(stringResource(MR.string.chat_view_once_open_on_phone_body)) },
            confirmButton = { TextButton(onClick = { explainOnPhone = false }) { Text(stringResource(MR.string.ok)) } },
        )
    }

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
                } else if (openOnPhone) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = openLabel,
                        role = Role.Button,
                        onClick = { explainOnPhone = true },
                    )
                } else Modifier
            )
            // After the clip and fill, so the bubble's own shape follows the growing width instead of being cut by it.
            .animateContentSize(motion.defaultSpatialSpec())
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = viewOnceLabel
                sentLabel?.let { stateDescription = it }
            }
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (authorName != null) {
            Text(
                text = authorName,
                style = MaterialTheme.typography.labelLarge.withContentDirection(),
                color = authorColor ?: contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        FooterTrailingOrBelow(footer = footer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = badge,
                    // One cookie carries unopened, sent and opened, so consuming it morphs in place rather than swapping.
                    contentKey = { if (it in COOKIE_FAMILY) Badge.Ready else it },
                    transitionSpec = {
                        (scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f) + fadeIn(HANDOFF_IN))
                            .togetherWith(scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f) + fadeOut(HANDOFF_OUT))
                    },
                    contentAlignment = Alignment.Center,
                ) { target ->
                    // Grows with the text up to a point, so at large font scales the glyph doesn't shrink beside the label.
                    val grow = LocalDensity.current.fontScale.coerceIn(1f, MAX_BADGE_GROWTH)
                    Box(Modifier.size(BADGE_SIZE * grow), contentAlignment = Alignment.Center) {
                        Box(Modifier.graphicsLayer { scaleX = grow; scaleY = grow }) {
                            ViewOnceBadge(
                                badge = target,
                                isOutgoing = isOutgoing,
                                pressMorph = pressMorph,
                                contentColor = contentColor,
                            )
                        }
                    }
                }
                // Title and status change as one unit, so a frame never pairs the new title with the old status.
                val sizeSpring = motion.defaultSpatialSpec<IntSize>()
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        (slideInVertically(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(HANDOFF_IN))
                            .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 3 } + fadeOut(HANDOFF_OUT))
                            .using(SizeTransform(clip = false) { _, _ -> sizeSpring })
                    },
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false),
                ) { shown -> BubbleTextBlock(shown) }
            }
        }
    }
}

private data class BubbleText(
    val title: String,
    val titleColor: Color,
    val status: String? = null,
    val statusColor: Color = titleColor,
    val statusInline: Boolean = false,
    val hint: String? = null,
    val hintColor: Color = titleColor,
    val spent: Boolean = false,
    val note: String? = null,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BubbleTextBlock(text: BubbleText) {
    val titleStyle = if (text.spent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleSmallEmphasized
    val statusStyle = MaterialTheme.typography.bodySmall
    val title = @Composable { modifier: Modifier ->
        Text(text = text.title, style = titleStyle.withContentDirection(), color = text.titleColor, modifier = modifier)
    }
    Column {
        if (text.status != null && text.statusInline) {
            // A short status rides on the title's baseline, and only wraps below at large font scales.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                title(Modifier.alignByBaseline())
                Text(
                    text = text.status,
                    style = statusStyle.withContentDirection(),
                    color = text.statusColor,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        } else {
            title(Modifier)
            if (text.status != null) {
                Text(
                    text = text.status,
                    style = statusStyle.withContentDirection(),
                    color = text.statusColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (text.hint != null) {
                Text(text = text.hint, style = statusStyle.withContentDirection(), color = text.hintColor)
            }
        }
        if (text.note != null) {
            Text(
                text = text.note,
                style = MaterialTheme.typography.labelSmall.withContentDirection(),
                color = text.statusColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// An English string inside an RTL layout keeps its own punctuation and ellipsis on the right side.
private fun TextStyle.withContentDirection() = copy(textDirection = TextDirection.Content)

@Composable
private fun ViewOnceBadge(badge: Badge, isOutgoing: Boolean, pressMorph: Float, contentColor: Color) {
    val colors = MaterialTheme.colorScheme
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    when (badge) {
        Badge.Ready, Badge.Sent, Badge.Opened -> CookieBadge(badge, isOutgoing, pressMorph, contentColor)
        Badge.Unavailable -> FilledBadge(cookie, colors.surfaceContainerHighest) {
            Icon(ViewOnceIcon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(GLYPH_SIZE))
        }
        Badge.Opening -> ViewOnceLoadingIndicator(BADGE_SIZE)
        Badge.Failed -> FilledBadge(cookie, colors.errorContainer) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(18.dp))
        }
        Badge.Expired -> FilledBadge(CircleShape, spentFill(isOutgoing, contentColor)) {
            val tint = if (isOutgoing) contentColor.copy(alpha = SPENT_ALPHA) else colors.onSurfaceVariant
            Icon(Icons.Outlined.TimerOff, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Badge.Unparseable -> FilledBadge(cookie, colors.surfaceContainerHighest) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The toggle's own dashed "1" on a cookie: filled for the recipient's unopened item, the one thing in the thread
 * worth tapping, tonal on the sender's copy. Opening morphs the cookie into the quiet circle every spent state sits
 * on, while the "1" turns into a check inside the same dashed ring.
 */
@Composable
private fun CookieBadge(badge: Badge, isOutgoing: Boolean, pressMorph: Float, contentColor: Color) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val spent = badge == Badge.Opened
    val fill by animateColorAsState(
        when {
            spent -> spentFill(isOutgoing, contentColor)
            isOutgoing -> contentColor.copy(alpha = SENT_FILL_ALPHA)
            else -> colors.primary
        },
        motion.defaultEffectsSpec(),
    )
    val glyph by animateColorAsState(
        when {
            spent && isOutgoing -> contentColor.copy(alpha = SPENT_ALPHA)
            spent -> colors.onSurfaceVariant
            isOutgoing -> contentColor
            else -> colors.onPrimary
        },
        motion.defaultEffectsSpec(),
    )
    val turn by animateFloatAsState(if (spent) 1f else 0f, motion.defaultSpatialSpec())
    val invite = remember { Animatable(if (badge == Badge.Ready) 0.72f else 1f) }
    val inviteSpec = motion.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { invite.animateTo(1f, inviteSpec) }
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Circle) }
    val shape = MorphShape(morph, maxOf(pressMorph, turn).coerceIn(0f, 1f))
    val swap = turn.coerceIn(0f, 1f)
    Box(
        Modifier.size(BADGE_SIZE).graphicsLayer { scaleX = invite.value; scaleY = invite.value },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = turn * 90f + (1f - invite.value) * -120f }
                .background(fill, shape),
        )
        Icon(
            ViewOnceIcon,
            contentDescription = null,
            tint = glyph,
            modifier = Modifier.size(GLYPH_SIZE).graphicsLayer {
                alpha = 1f - swap
                rotationZ = turn * 90f
            },
        )
        Icon(
            ViewOnceOpenedIcon,
            contentDescription = null,
            tint = glyph,
            modifier = Modifier.size(GLYPH_SIZE).graphicsLayer {
                alpha = swap
                val pop = 0.7f + 0.3f * turn
                scaleX = pop
                scaleY = pop
            },
        )
    }
}

@Composable
private fun spentFill(isOutgoing: Boolean, contentColor: Color): Color =
    if (isOutgoing) contentColor.copy(alpha = SPENT_FILL_ALPHA) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SPENT_FILL_ALPHA)

/** The bubble and the viewer share it, so opening reads the same in both places. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ViewOnceLoadingIndicator(size: Dp, modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier.size(size), color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun FilledBadge(shape: Shape, fill: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(BADGE_SIZE).background(fill, shape), contentAlignment = Alignment.Center) { content() }
}

private val BADGE_SIZE = 32.dp
private const val GLYPH_RATIO = 0.72f
private val GLYPH_SIZE = BADGE_SIZE * GLYPH_RATIO
private const val SENT_FILL_ALPHA = 0.18f
private const val SPENT_FILL_ALPHA = 0.1f
private const val MUTED_ALPHA = 0.8f
private const val MAX_BADGE_GROWTH = 1.4f
private const val SPENT_ALPHA = 0.7f
private val COOKIE_FAMILY = setOf(Badge.Ready, Badge.Sent, Badge.Opened)

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
