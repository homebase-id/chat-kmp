@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.chat.calling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallMissedOutgoing
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import id.homebase.resources.MR
import id.homebase.resources.call_button_video
import id.homebase.resources.call_button_voice
import id.homebase.resources.call_call_back
import id.homebase.resources.call_duration_hours
import id.homebase.resources.call_duration_minutes
import id.homebase.resources.call_duration_seconds
import id.homebase.resources.call_event_busy
import id.homebase.resources.call_event_cancelled
import id.homebase.resources.call_event_declined
import id.homebase.resources.call_event_duration
import id.homebase.resources.call_event_failed
import id.homebase.resources.call_event_incoming
import id.homebase.resources.call_event_missed
import id.homebase.resources.call_event_no_answer
import id.homebase.resources.call_event_outgoing
import id.homebase.resources.call_event_unparseable
import org.jetbrains.compose.resources.stringResource

/** [isOwn] is true when this device's user placed the call (the author of the log message). */
@Composable
fun CallEventBubble(
    descriptor: CallEventDescriptor?,
    isOwn: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onCallBack: (() -> Unit)? = null,
    footer: (@Composable (Color) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    if (descriptor == null) {
        CallEventLayout(
            modifier, shape, containerColor, contentColor,
            icon = Icons.Outlined.Update, iconTint = contentColor.copy(alpha = 0.72f),
            title = stringResource(MR.string.call_event_unparseable), titleStyle = MaterialTheme.typography.bodyMedium,
            titleColor = contentColor, subtitle = null, footer = footer, trailing = null,
        )
        return
    }
    val missedForMe = !isOwn && (descriptor.outcome == CallOutcome.NoAnswer || descriptor.outcome == CallOutcome.Cancelled)
    val icon: ImageVector = when {
        missedForMe -> Icons.AutoMirrored.Filled.CallMissed
        descriptor.outcome == CallOutcome.NoAnswer -> Icons.AutoMirrored.Filled.CallMissedOutgoing
        descriptor.outcome == CallOutcome.Cancelled -> Icons.Outlined.Cancel
        descriptor.outcome == CallOutcome.Declined -> Icons.Default.CallEnd
        descriptor.outcome == CallOutcome.Busy -> Icons.Default.PhoneDisabled
        descriptor.outcome == CallOutcome.Failed -> Icons.Outlined.ErrorOutline
        isOwn -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    val title = stringResource(
        when (descriptor.outcome) {
            CallOutcome.Completed -> if (isOwn) MR.string.call_event_outgoing else MR.string.call_event_incoming
            CallOutcome.NoAnswer -> if (isOwn) MR.string.call_event_no_answer else MR.string.call_event_missed
            CallOutcome.Cancelled -> if (isOwn) MR.string.call_event_cancelled else MR.string.call_event_missed
            CallOutcome.Failed -> MR.string.call_event_failed
            CallOutcome.Declined -> MR.string.call_event_declined
            CallOutcome.Busy -> MR.string.call_event_busy
        },
    )
    val kind = stringResource(if (descriptor.video) MR.string.call_button_video else MR.string.call_button_voice)
    val duration = if (descriptor.outcome == CallOutcome.Completed && descriptor.durationSeconds > 0) {
        spokenCallDuration(descriptor.durationSeconds)
    } else null
    val subtitle = CallSubtitle(kind, duration, duration?.let { stringResource(MR.string.call_event_duration, kind, it) })

    val callBackLabel = stringResource(MR.string.call_call_back)
    CallEventLayout(
        modifier, shape, containerColor, contentColor,
        icon = icon,
        iconTint = if (missedForMe) colors.error else contentColor.copy(alpha = 0.72f),
        title = title,
        titleStyle = MaterialTheme.typography.titleSmall,
        titleColor = if (missedForMe) colors.error else contentColor,
        subtitle = subtitle,
        footer = footer,
        trailing = if (onCallBack == null) null else {
            {
                val (container, content) = when {
                    missedForMe -> colors.primary to colors.onPrimary
                    isOwn -> contentColor.copy(alpha = 0.24f) to contentColor
                    else -> colors.secondaryContainer to colors.onSecondaryContainer
                }
                FilledTonalIconButton(
                    onClick = onCallBack,
                    modifier = Modifier.size(48.dp),
                    shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(16.dp), pressedShape = RoundedCornerShape(10.dp)),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = container, contentColor = content),
                ) {
                    Icon(if (descriptor.video) Icons.Default.Videocam else Icons.Default.Call, callBackLabel, Modifier.size(22.dp))
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallEventLayout(
    modifier: Modifier,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    titleStyle: TextStyle,
    titleColor: Color,
    subtitle: CallSubtitle?,
    footer: (@Composable (Color) -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val secondary = contentColor.copy(alpha = 0.72f)
    Surface(modifier.widthIn(min = 200.dp), shape = shape, color = containerColor, contentColor = contentColor) {
        Row(
            Modifier.padding(start = 14.dp, end = if (trailing != null) 8.dp else 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val iconSize = with(LocalDensity.current) { 18.sp.toDp() }
                Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(iconSize), tint = iconTint)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = titleStyle.copy(textDirection = TextDirection.Content), color = titleColor)
                    if (subtitle == null) {
                        footer?.let { Box(Modifier.align(Alignment.End)) { it(secondary) } }
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            itemVerticalAlignment = Alignment.Bottom,
                        ) {
                            KindAndDuration(subtitle, MaterialTheme.typography.bodySmall.copy(color = secondary, textDirection = TextDirection.Content))
                            footer?.invoke(secondary)
                        }
                    }
                }
            }
            trailing?.invoke()
        }
    }
}

private class CallSubtitle(val kind: String, val duration: String?, val joined: String?)

/** "Voice call · 12 min 34 s" on one line when it fits, else kind over duration, so neither half is ever split. */
@Composable
private fun KindAndDuration(subtitle: CallSubtitle, style: TextStyle) {
    if (subtitle.duration == null || subtitle.joined == null) {
        Text(subtitle.kind.nonBreaking(), style = style)
        return
    }
    Layout(
        content = {
            Text(subtitle.joined.nonBreaking(), style = style, softWrap = false)
            Text(subtitle.kind.nonBreaking(), style = style)
            Text(subtitle.duration.nonBreaking(), style = style)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0)
        val joined = measurables[0].measure(loose.copy(maxWidth = Constraints.Infinity))
        if (joined.width <= constraints.maxWidth) {
            layout(joined.width, joined.height) { joined.place(0, 0) }
        } else {
            val kind = measurables[1].measure(loose)
            val duration = measurables[2].measure(loose)
            layout(maxOf(kind.width, duration.width), kind.height + duration.height) {
                kind.placeRelative(0, 0)
                duration.placeRelative(0, kind.height)
            }
        }
    }
}

private fun String.nonBreaking() = replace(' ', '\u00A0')

@Composable
internal fun spokenCallDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> stringResource(MR.string.call_duration_hours, h, m)
        m > 0 -> stringResource(MR.string.call_duration_minutes, m, s)
        else -> stringResource(MR.string.call_duration_seconds, s)
    }
}
