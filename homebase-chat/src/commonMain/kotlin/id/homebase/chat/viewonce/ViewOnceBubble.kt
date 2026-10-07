package id.homebase.chat.viewonce

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
    // Spent rows step back on both sides, so a live item above them stays the strongest thing in the thread.
    val spentColor = if (isOutgoing) contentColor.copy(alpha = SPENT_ALPHA) else colors.onSurfaceVariant
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
                Badge.Sent -> stringResource(MR.string.chat_view_once_sent)
                else -> stringResource(if (openOnPhone) MR.string.chat_view_once_open_on_phone else MR.string.chat_view_once_unavailable)
            },
            statusColor = if (badge == Badge.Failed) colors.error else mutedColor,
            statusInline = badge == Badge.Sent || badge == Badge.Opening,
            phoneIcon = badge == Badge.Unavailable && openOnPhone,
            note = shotNote,
        )
    }

    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec())
    val pressMorph by animateFloatAsState(if (pressed) 1f else 0f, motion.fastSpatialSpec())
    val openLabel = stringResource(MR.string.cd_view_once_open)

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
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {}
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
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        (slideInVertically(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(HANDOFF_IN))
                            .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 3 } + fadeOut(HANDOFF_OUT))
                            .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
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
    val phoneIcon: Boolean = false,
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
                val lineHeight = with(LocalDensity.current) { statusStyle.lineHeight.toDp() }
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 2.dp)) {
                    if (text.phoneIcon) {
                        // Sized to one line so a wrapped label keeps the icon beside its first line.
                        Box(Modifier.padding(end = 4.dp).height(lineHeight), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.PhoneAndroid,
                                contentDescription = null,
                                tint = text.statusColor,
                                modifier = Modifier.size(lineHeight * 0.9f),
                            )
                        }
                    }
                    Text(text = text.status, style = statusStyle.withContentDirection(), color = text.statusColor)
                }
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
    val badgeModifier = Modifier.size(BADGE_SIZE)
    when (badge) {
        Badge.Ready, Badge.Sent, Badge.Opened -> CookieBadge(badge, isOutgoing, pressMorph, contentColor)
        Badge.Unavailable -> RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), colors.outline) {
            DigitIcon(ViewOnceDigitIcon, colors.onSurfaceVariant)
        }
        Badge.Opening -> ViewOnceLoadingIndicator(BADGE_SIZE)
        Badge.Failed -> RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), colors.error) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
        }
        Badge.Expired -> {
            val ring = if (isOutgoing) contentColor.copy(alpha = SPENT_ALPHA) else colors.outline
            val glyph = if (isOutgoing) contentColor.copy(alpha = SPENT_ALPHA) else colors.onSurfaceVariant
            RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), ring) {
                Icon(Icons.Outlined.TimerOff, contentDescription = null, tint = glyph, modifier = Modifier.size(17.dp))
            }
        }
        Badge.Unparseable -> RingBadge(badgeModifier, MaterialShapes.Cookie9Sided.toShape(), colors.outline) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Filled only for the recipient's unopened item, the one thing in the thread worth tapping. The sender's copy is an
 * outline, and opening empties the cookie while it turns a third (the 9-sided cookie lands on its own silhouette).
 */
@Composable
private fun CookieBadge(badge: Badge, isOutgoing: Boolean, pressMorph: Float, contentColor: Color) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val spent = badge == Badge.Opened
    val fill by animateFloatAsState(if (badge == Badge.Ready) 1f else 0f, motion.defaultEffectsSpec())
    val turn by animateFloatAsState(if (spent) 1f else 0f, motion.slowSpatialSpec())
    val ring by animateColorAsState(
        when {
            isOutgoing -> contentColor.copy(alpha = if (spent) SPENT_ALPHA else 0.85f)
            spent -> colors.outline
            else -> colors.primary
        },
        motion.defaultEffectsSpec(),
    )
    val digitAlpha by animateFloatAsState(if (spent) 0f else 1f, motion.fastEffectsSpec())
    val invite = remember { Animatable(if (badge == Badge.Ready) 0.72f else 1f) }
    val inviteSpec = motion.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { invite.animateTo(1f, inviteSpec) }
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Circle) }
    // Rounds out mid-turn, so the cookie visibly changes form while it empties.
    val squish = (4f * turn * (1f - turn)).coerceIn(0f, 1f) * 0.7f
    val shape = MorphShape(morph, maxOf(pressMorph, squish))
    val fillColor = colors.primary
    Box(
        Modifier.size(BADGE_SIZE).graphicsLayer { scaleX = invite.value; scaleY = invite.value },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = turn * 120f + (1f - invite.value) * -120f }
                .background(fillColor.copy(alpha = fill.coerceIn(0f, 1f)), shape)
                .border(1.5.dp, ring, shape),
        )
        val digitTint = if (isOutgoing) contentColor else lerp(colors.primary, colors.onPrimary, fill.coerceIn(0f, 1f))
        DigitIcon(ViewOnceDigitIcon, digitTint, Modifier.graphicsLayer { alpha = digitAlpha })
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = if (isOutgoing) contentColor.copy(alpha = SPENT_ALPHA) else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp).graphicsLayer {
                alpha = 1f - digitAlpha
                val pop = 0.6f + 0.4f * turn
                scaleX = pop
                scaleY = pop
            },
        )
    }
}

/** The bubble and the viewer share it: the item's own "1" cookie, turning and breathing while it opens. */
@Composable
internal fun ViewOnceLoadingIndicator(size: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val loop = rememberInfiniteTransition()
    val angle by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(LOADING_TURN_MS, easing = LinearEasing)))
    val breathe by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(LOADING_TURN_MS / 4, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Cookie4Sided) }
    val shape = MorphShape(morph, breathe)
    Box(
        modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().graphicsLayer { rotationZ = angle }.background(colors.primary, shape))
        Icon(ViewOnceDigitIcon, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(size * DIGIT_RATIO))
    }
}

@Composable
private fun RingBadge(modifier: Modifier, shape: Shape, ringColor: Color, content: @Composable () -> Unit) {
    Box(modifier.border(1.5.dp, ringColor, shape), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun DigitIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(BADGE_SIZE * DIGIT_RATIO))
}

private val BADGE_SIZE = 32.dp
private const val DIGIT_RATIO = 0.8f
private const val MUTED_ALPHA = 0.8f
private const val MAX_BADGE_GROWTH = 1.4f
private const val SPENT_ALPHA = 0.7f
private const val LOADING_TURN_MS = 2_400
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
