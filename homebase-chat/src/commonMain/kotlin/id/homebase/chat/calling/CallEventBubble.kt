package id.homebase.chat.calling

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.CallReceived
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
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.call_event_busy
import id.homebase.resources.call_event_cancelled
import id.homebase.resources.call_event_declined
import id.homebase.resources.call_event_duration
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
        missedForMe -> Icons.AutoMirrored.Filled.CallMissed
        descriptor.video -> Icons.Default.Videocam
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
            CallOutcome.NoAnswer, CallOutcome.Cancelled ->
                if (isOwn) MR.string.call_event_no_answer
                else if (descriptor.video) MR.string.call_event_missed_video else MR.string.call_event_missed
            CallOutcome.Declined -> MR.string.call_event_declined
            CallOutcome.Busy -> MR.string.call_event_busy
        },
    )
    val subtitle = if (descriptor.outcome == CallOutcome.Completed && descriptor.durationSeconds > 0) {
        formatCallDuration(descriptor.durationSeconds)
    } else null
    val badgeColor = if (missedForMe) MaterialTheme.colorScheme.errorContainer else contentColor.copy(alpha = 0.1f)
    val iconTint = if (missedForMe) MaterialTheme.colorScheme.onErrorContainer else contentColor

    Surface(modifier.widthIn(min = 200.dp), shape = shape, color = containerColor, contentColor = contentColor) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconTint)
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (missedForMe) MaterialTheme.colorScheme.error else contentColor,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = contentColor.copy(alpha = 0.72f),
                        )
                    }
                }
            }
            footer?.invoke(contentColor.copy(alpha = 0.72f))
        }
    }
}
