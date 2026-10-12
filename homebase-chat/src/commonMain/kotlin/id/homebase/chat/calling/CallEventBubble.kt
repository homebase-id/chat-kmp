package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
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
            modifier, shape, containerColor, contentColor, Modifier.semantics(mergeDescendants = true) {},
            icon = Icons.Default.Warning, badgeColor = contentColor.copy(alpha = 0.12f), iconTint = contentColor,
            title = stringResource(MR.string.call_event_unparseable), titleStyle = MaterialTheme.typography.bodyMedium,
            titleColor = contentColor, subtitle = null, footer = footer, trailing = null,
        )
        return
    }
    val missed = descriptor.outcome == CallOutcome.NoAnswer || descriptor.outcome == CallOutcome.Cancelled
    val missedForMe = missed && !isOwn
    val icon: ImageVector = when {
        descriptor.outcome == CallOutcome.Declined -> Icons.Default.CallEnd
        descriptor.outcome == CallOutcome.Busy -> Icons.Default.PhoneDisabled
        descriptor.outcome == CallOutcome.Failed -> Icons.Default.Warning
        missedForMe -> Icons.AutoMirrored.Filled.CallMissed
        missed -> Icons.AutoMirrored.Filled.CallMissedOutgoing
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
    val subtitle = if (descriptor.outcome == CallOutcome.Completed && descriptor.durationSeconds > 0) {
        // Non-breaking so a narrow bubble wraps between kind and duration, never inside "12 min".
        stringResource(MR.string.call_event_duration, kind, spokenCallDuration(descriptor.durationSeconds).replace(' ', '\u00A0'))
    } else kind

    val callBackLabel = stringResource(MR.string.call_call_back)
    CallEventLayout(
        modifier, shape, containerColor, contentColor,
        interaction = if (onCallBack != null) {
            Modifier.clickable(onClickLabel = callBackLabel, role = Role.Button, onClick = onCallBack)
        } else Modifier.semantics(mergeDescendants = true) {},
        icon = icon,
        badgeColor = if (missedForMe) colors.error.copy(alpha = 0.14f) else contentColor.copy(alpha = 0.12f),
        iconTint = if (missedForMe) colors.error else contentColor,
        title = title,
        titleStyle = MaterialTheme.typography.titleSmall,
        titleColor = if (missedForMe) colors.error else contentColor,
        subtitle = subtitle,
        footer = footer,
        trailing = if (onCallBack == null) null else {
            {
                val (actionColor, onAction) =
                    if (missedForMe) colors.primary to colors.onPrimary else contentColor.copy(alpha = 0.12f) to contentColor
                Box(Modifier.size(40.dp).background(actionColor, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (descriptor.video) Icons.Default.Videocam else Icons.Default.Call, null, Modifier.size(20.dp), tint = onAction)
                }
            }
        },
    )
}

@Composable
private fun CallEventLayout(
    modifier: Modifier,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    interaction: Modifier,
    icon: ImageVector,
    badgeColor: Color,
    iconTint: Color,
    title: String,
    titleStyle: TextStyle,
    titleColor: Color,
    subtitle: String?,
    footer: (@Composable (Color) -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val secondary = contentColor.copy(alpha = 0.72f)
    Surface(modifier.widthIn(min = 232.dp), shape = shape, color = containerColor, contentColor = contentColor) {
        Row(
            interaction.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = iconTint)
            }
            Column(Modifier.weight(1f, fill = false).width(IntrinsicSize.Max)) {
                Text(title, style = titleStyle, color = titleColor)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = secondary, modifier = Modifier.weight(1f))
                    if (footer != null) {
                        Spacer(Modifier.width(12.dp))
                        footer(secondary)
                    }
                }
            }
            trailing?.invoke()
        }
    }
}

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
