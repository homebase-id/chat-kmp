package id.homebase.chat.viewonce

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import id.homebase.resources.chat_view_once_photo
import id.homebase.resources.chat_view_once_screenshot_taken
import id.homebase.resources.chat_view_once_sent
import id.homebase.resources.chat_view_once_tap_to_retry
import id.homebase.resources.chat_view_once_unavailable
import id.homebase.resources.chat_view_once_unparseable
import id.homebase.resources.chat_view_once_update_to_open
import id.homebase.resources.chat_view_once_video
import id.homebase.resources.ok
import org.jetbrains.compose.resources.pluralStringResource
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
    state: ViewOnceState = if (isOutgoing) ViewOnceState.Sent else ViewOnceState.Unopened,
    openedCount: Int = 0,
    screenshotTaken: Boolean = false,
    canView: Boolean = true,
    phase: ViewOnceOpenPhase = ViewOnceOpenPhase.Idle,
    onOpen: (() -> Unit)? = null,
    authorName: String? = null,
    authorColor: Color? = null,
    footer: @Composable (contentColor: Color) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val consumed = state == ViewOnceState.Opened || state == ViewOnceState.Expired
    val container by animateColorAsState(if (consumed) colors.surfaceContainerHigh else containerColor, motion.defaultEffectsSpec())
    val content by animateColorAsState(if (consumed) colors.onSurfaceVariant else contentColor, motion.defaultEffectsSpec())
    // The sender never opens its own copy, and a consumed or desktop copy has nothing left to open.
    val canOpen = onOpen != null && descriptor != null && !isOutgoing && !consumed && canView
    val openOnPhone = !canView && descriptor != null && !isOutgoing && !consumed
    var explainOnPhone by remember { mutableStateOf(false) }
    val opening = canOpen && phase == ViewOnceOpenPhase.Opening
    val failed = canOpen && phase == ViewOnceOpenPhase.Failed

    // A consumed tombstone may have lost its kind; the generic word still says what the pill was.
    val kindLabel = stringResource(
        when (descriptor?.kind) {
            ViewOnceDescriptor.KIND_VIDEO -> MR.string.chat_view_once_video
            ViewOnceDescriptor.KIND_IMAGE -> MR.string.chat_view_once_photo
            else -> MR.string.chat_view_once_unparseable
        },
    )
    val title: String
    val second: String?
    val status: List<String>
    when {
        consumed -> {
            title = when {
                state == ViewOnceState.Expired -> stringResource(MR.string.chat_view_once_expired)
                isOutgoing && openedCount > 1 -> pluralStringResource(MR.plurals.chat_view_once_opened_by, openedCount, openedCount)
                else -> stringResource(MR.string.chat_view_once_opened)
            }
            second = kindLabel
            status = emptyList()
        }
        descriptor == null -> {
            title = kindLabel
            second = null
            status = listOf(stringResource(MR.string.chat_view_once_update_to_open))
        }
        else -> {
            title = kindLabel
            second = null
            status = when {
                opening -> listOf(stringResource(MR.string.chat_view_once_opening))
                failed -> listOf(stringResource(MR.string.chat_view_once_failed), stringResource(MR.string.chat_view_once_tap_to_retry))
                openOnPhone -> listOf(stringResource(MR.string.chat_view_once_open_on_phone))
                !isOutgoing && !canOpen -> listOf(stringResource(MR.string.chat_view_once_unavailable))
                else -> emptyList()
            }
        }
    }
    val shotNote = if (screenshotTaken) stringResource(MR.string.chat_view_once_screenshot_taken) else null

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.97f else 1f, motion.fastSpatialSpec())
    val openLabel = stringResource(MR.string.cd_view_once_open)
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
        consumed -> content
        !isOutgoing && canOpen -> colors.primary
        else -> content
    }
    val glyph: ImageVector? = when {
        opening -> null
        failed -> Icons.Default.Refresh
        state == ViewOnceState.Expired -> Icons.Outlined.TimerOff
        consumed -> ViewOnceOpenedIcon
        else -> ViewOnceIcon
    }
    val statusColor = if (failed) colors.error else content.copy(alpha = STATUS_ALPHA)

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape)
            .background(container)
            .then(
                when {
                    canOpen && !opening -> Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = openLabel,
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    openOnPhone -> Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = openLabel,
                        role = Role.Button,
                        onClick = { explainOnPhone = true },
                    )
                    else -> Modifier
                },
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
                    Box(Modifier.size(GLYPH_SIZE), contentAlignment = Alignment.Center) {
                        if (glyph == null) {
                            ViewOnceLoadingIndicator(GLYPH_SIZE)
                        } else {
                            Icon(glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.size(GLYPH_SIZE))
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
                val lines = status + listOfNotNull(shotNote)
                if (lines.isNotEmpty()) {
                    Column(Modifier.padding(start = GLYPH_SIZE + 8.dp, top = 2.dp)) {
                        lines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.withContentDirection(),
                                color = statusColor,
                            )
                        }
                    }
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

private val GLYPH_SIZE = 22.dp
private const val STATUS_ALPHA = 0.72f

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
