package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.call_call_back
import id.homebase.resources.call_duration_hours
import id.homebase.resources.call_duration_minutes
import id.homebase.resources.call_duration_seconds
import id.homebase.resources.call_event_busy
import id.homebase.resources.call_event_cancelled
import id.homebase.resources.call_event_declined
import id.homebase.resources.call_event_failed
import id.homebase.resources.call_event_incoming
import id.homebase.resources.call_event_incoming_video
import id.homebase.resources.call_event_missed
import id.homebase.resources.call_event_missed_video
import id.homebase.resources.call_event_no_answer
import id.homebase.resources.call_event_outgoing
import id.homebase.resources.call_event_outgoing_video
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
    if (descriptor == null) {
        Surface(modifier, shape = shape, color = containerColor, contentColor = contentColor) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = contentColor.copy(alpha = 0.7f))
                Text(stringResource(MR.string.call_event_unparseable), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                footer?.invoke(contentColor.copy(alpha = 0.72f))
            }
        }
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
            CallOutcome.Completed -> when {
                descriptor.video && isOwn -> MR.string.call_event_outgoing_video
                descriptor.video -> MR.string.call_event_incoming_video
                isOwn -> MR.string.call_event_outgoing
                else -> MR.string.call_event_incoming
            }
            CallOutcome.NoAnswer ->
                if (isOwn) MR.string.call_event_no_answer
                else if (descriptor.video) MR.string.call_event_missed_video else MR.string.call_event_missed
            CallOutcome.Cancelled ->
                if (isOwn) MR.string.call_event_cancelled
                else if (descriptor.video) MR.string.call_event_missed_video else MR.string.call_event_missed
            CallOutcome.Failed -> MR.string.call_event_failed
            CallOutcome.Declined -> MR.string.call_event_declined
            CallOutcome.Busy -> MR.string.call_event_busy
        },
    )
    val subtitle = if (descriptor.outcome == CallOutcome.Completed && descriptor.durationSeconds > 0) {
        spokenCallDuration(descriptor.durationSeconds)
    } else null
    val badgeColor = if (missedForMe) MaterialTheme.colorScheme.errorContainer else contentColor.copy(alpha = 0.1f)
    val iconTint = if (missedForMe) MaterialTheme.colorScheme.onErrorContainer else contentColor

    val callBackLabel = stringResource(MR.string.call_call_back)
    val clickable = if (onCallBack != null) {
        Modifier.clickable(onClickLabel = callBackLabel, role = Role.Button, onClick = onCallBack)
    } else Modifier
    Surface(modifier.widthIn(min = 200.dp), shape = shape, color = containerColor, contentColor = contentColor) {
        Row(
            clickable.padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CallEventSummary(icon, badgeColor, iconTint, title, subtitle, if (missedForMe) MaterialTheme.colorScheme.error else contentColor, contentColor)
            if (onCallBack != null) {
                Box(
                    Modifier.padding(bottom = 4.dp).size(32.dp).background(contentColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (descriptor.video) Icons.Default.Videocam else Icons.Default.Call, null, Modifier.size(18.dp), tint = contentColor)
                }
            }
            footer?.invoke(contentColor.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun CallEventSummary(
    icon: ImageVector,
    badgeColor: Color,
    iconTint: Color,
    title: String,
    subtitle: String?,
    titleColor: Color,
    contentColor: Color,
) {
    Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconTint)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.72f))
            }
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
