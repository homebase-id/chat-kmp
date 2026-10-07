package id.homebase.chat.viewonce

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.resources.MR
import id.homebase.resources.cd_view_once_open
import id.homebase.resources.cd_view_once_toggle
import id.homebase.resources.chat_view_once_expired
import id.homebase.resources.chat_view_once_failed
import id.homebase.resources.chat_view_once_open_on_phone
import id.homebase.resources.chat_view_once_open_on_phone_body
import id.homebase.resources.chat_view_once_open_on_phone_title
import id.homebase.resources.chat_view_once_opened
import id.homebase.resources.chat_view_once_opened_by
import id.homebase.resources.chat_view_once_opening
import id.homebase.resources.chat_view_once_sent
import id.homebase.resources.chat_view_once_retry
import id.homebase.resources.chat_view_once_unavailable
import id.homebase.resources.chat_view_once_update_to_open
import id.homebase.resources.ok
import org.jetbrains.compose.resources.stringResource

enum class ViewOnceOpenPhase { Idle, Opening, Failed }

/**
 * A compact pill, the same on both sides: the view-once glyph, what it is, and the time. Once spent
 * it steps back to a quiet tonal pill whichever side sent it. Placeholder only: it never reads the
 * message payload.
 */
@Composable
fun ViewOnceBubble(
    descriptor: ViewOnceDescriptor?,
    isOutgoing: Boolean,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
    state: ViewOnceState = if (isOutgoing) ViewOnceState.Sent else ViewOnceState.Unopened,
    openedCount: Int = 0,
    canView: Boolean = true,
    phase: ViewOnceOpenPhase = ViewOnceOpenPhase.Idle,
    onOpen: (() -> Unit)? = null,
    // The pill's own click handler would swallow the press, so a long press has to be handed up from here.
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    authorName: String? = null,
    authorColor: Color? = null,
    footer: @Composable (contentColor: Color) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val consumed = state == ViewOnceState.Opened || state == ViewOnceState.Expired
    val content by animateColorAsState(if (consumed) colors.onSurfaceVariant else contentColor, motion.defaultEffectsSpec())
    // The sender never opens its own copy, and a consumed or desktop copy has nothing left to open.
    val openable = descriptor != null && !isOutgoing && !consumed
    val canOpen = openable && onOpen != null && canView
    val openOnPhone = openable && !canView
    var explainOnPhone by remember { mutableStateOf(false) }
    val opening = canOpen && phase == ViewOnceOpenPhase.Opening
    val failed = canOpen && phase == ViewOnceOpenPhase.Failed

    val kindLabel = stringResource(descriptor.kindLabel())
    val title: String
    val second: String?
    val status: String?
    when {
        consumed -> {
            title = when {
                state == ViewOnceState.Expired -> stringResource(MR.string.chat_view_once_expired)
                isOutgoing && isGroup && openedCount >= 1 -> stringResource(MR.string.chat_view_once_opened_by, openedCount)
                else -> stringResource(MR.string.chat_view_once_opened)
            }
            second = kindLabel
            status = null
        }
        descriptor == null -> {
            title = kindLabel
            second = null
            status = stringResource(MR.string.chat_view_once_update_to_open)
        }
        else -> {
            title = kindLabel
            second = null
            status = when {
                opening -> stringResource(MR.string.chat_view_once_opening)
                failed -> stringResource(MR.string.chat_view_once_failed)
                openOnPhone -> stringResource(MR.string.chat_view_once_open_on_phone)
                !isOutgoing && !canOpen -> stringResource(MR.string.chat_view_once_unavailable)
                else -> null
            }
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec())
    val container by animateColorAsState(
        when {
            // M3 pressed state layer over the highest container, so the press reads in light theme too.
            pressed -> colors.onSurface.copy(alpha = PRESSED_STATE_ALPHA).compositeOver(colors.surfaceContainerHighest)
            consumed -> colors.surfaceContainerHigh
            else -> containerColor
        },
        if (pressed) motion.fastEffectsSpec() else motion.defaultEffectsSpec(),
    )
    // The glyph carries the meaning, so it grows with the label at large font scales.
    val glyphSize = with(LocalDensity.current) { GLYPH_SIZE.toDp() }
    val openLabel = stringResource(if (failed) MR.string.chat_view_once_retry else MR.string.cd_view_once_open)
    val viewOnceLabel = stringResource(MR.string.cd_view_once_toggle)
    val sentLabel = if (isOutgoing && !consumed && descriptor != null) stringResource(MR.string.chat_view_once_sent) else null

    if (explainOnPhone) {
        AlertDialog(
            onDismissRequest = { explainOnPhone = false },
            title = { Text(stringResource(MR.string.chat_view_once_open_on_phone_title)) },
            text = { Text(stringResource(MR.string.chat_view_once_open_on_phone_body)) },
            confirmButton = { TextButton(onClick = { explainOnPhone = false }) { Text(stringResource(MR.string.ok)) } },
        )
    }

    val glyphTint = when {
        failed -> colors.error
        canOpen -> colors.primary
        else -> content
    }
    val glyph: ImageVector = when {
        state == ViewOnceState.Expired -> Icons.Outlined.TimerOff
        consumed -> ViewOnceOpenedIcon
        else -> ViewOnceIcon
    }
    val statusColor = if (failed) colors.error else content.copy(alpha = STATUS_ALPHA)
    val onClick: (() -> Unit)? = when {
        canOpen && !opening -> onOpen
        openOnPhone -> { { explainOnPhone = true } }
        else -> null
    }

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape)
            .background(container)
            .then(
                onClick?.let {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = openLabel,
                        role = Role.Button,
                        onClick = it,
                        onLongClick = onLongClick,
                        onDoubleClick = onDoubleClick,
                    )
                } ?: Modifier,
            )
            .animateContentSize(motion.defaultSpatialSpec())
            .semantics(mergeDescendants = true) {
                contentDescription = viewOnceLabel
                sentLabel?.let { stateDescription = it }
            }
            .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (authorName != null) {
            Text(
                text = authorName,
                style = MaterialTheme.typography.labelLarge.withContentDirection(),
                color = authorColor ?: content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
            )
        }
        FooterCentredOrBelow(footer = { footer(content) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The "1" stays through opening and failure; progress and retry only adorn it.
                    Box(Modifier.size(glyphSize), contentAlignment = Alignment.Center) {
                        Icon(glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.size(glyphSize))
                        if (opening) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(glyphSize),
                                color = colors.primary,
                                strokeWidth = ADORNMENT_STROKE,
                            )
                        }
                        if (failed) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = colors.onError,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = ADORNMENT_OFFSET, y = ADORNMENT_OFFSET)
                                    .size(glyphSize / 2)
                                    .background(colors.error, CircleShape)
                                    .padding(ADORNMENT_PADDING),
                            )
                        }
                    }
                    Text(
                        text = title,
                        style = (if (consumed) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall).withContentDirection(),
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    if (second != null) {
                        Box(
                            Modifier.padding(horizontal = 6.dp).size(3.dp).background(content.copy(alpha = STATUS_ALPHA), CircleShape),
                        )
                        Text(
                            text = second,
                            style = MaterialTheme.typography.bodyMedium.withContentDirection(),
                            color = content.copy(alpha = STATUS_ALPHA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                status?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.withContentDirection(),
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = glyphSize + 8.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

// An English string inside an RTL layout keeps its own punctuation and ellipsis on the right side.
private fun TextStyle.withContentDirection() = copy(textDirection = TextDirection.Content)

/** The bubble and the viewer share it, so opening reads the same in both places. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ViewOnceLoadingIndicator(size: Dp, modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier.size(size), color = MaterialTheme.colorScheme.primary)
}

private val GLYPH_SIZE = 22.sp
private val ADORNMENT_STROKE = 2.dp
private val ADORNMENT_OFFSET = 3.dp
private val ADORNMENT_PADDING = 1.dp
private const val STATUS_ALPHA = 0.72f
private const val PRESSED_STATE_ALPHA = 0.10f

// The timestamp sits beside the content on its vertical centre, and drops below when that would squeeze it.
@Composable
private fun FooterCentredOrBelow(footer: @Composable () -> Unit, content: @Composable () -> Unit) {
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
            footerPlaceable?.placeRelative(
                layoutWidth - footerWidth,
                if (inline) (height - footerHeight) / 2 else height - footerHeight,
            )
        }
    }
}
