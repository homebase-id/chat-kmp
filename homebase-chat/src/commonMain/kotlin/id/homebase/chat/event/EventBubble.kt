package id.homebase.chat.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_event_unparseable
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * In-stream bubble for an Event message. Renders entirely from data already on
 * the message header — no payload fetch on scroll.
 *
 * @param descriptor Parsed event payload, or null when the descriptor failed to
 *   parse (older client / schema drift). When null we render a minimal fallback
 *   chip so the message doesn't disappear from the stream.
 * @param onClick Open the detail screen.
 * @param contentColor Color for foreground text/icons (primary surface text).
 * @param containerColor Background color for the card (typically a Material 3
 *   surface variant).
 */
@Composable
fun EventBubble(
    descriptor: EventDescriptor?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    if (descriptor == null) {
        UnparseableEventBubble(modifier = modifier, contentColor = contentColor, containerColor = containerColor)
        return
    }

    val tz = remember(descriptor.timezone) {
        runCatching { TimeZone.of(descriptor.timezone) }.getOrElse { TimeZone.currentSystemDefault() }
    }
    val startLocal = remember(descriptor.startUtcMs, tz) {
        Instant.fromEpochMilliseconds(descriptor.startUtcMs).toLocalDateTime(tz)
    }
    val endLocal = remember(descriptor.endUtcMs, tz) {
        descriptor.endUtcMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz) }
    }

    val baseModifier = modifier
        .widthIn(min = 240.dp, max = 320.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(containerColor)
        .let { if (onClick != null) it.then(Modifier.clickableNoRipple(onClick)) else it }
        .padding(12.dp)

    Row(modifier = baseModifier, verticalAlignment = Alignment.Top) {
        DateChip(startLocal, contentColor)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = descriptor.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            TimeRow(startLocal = startLocal, endLocal = endLocal, timezone = descriptor.timezone, contentColor = contentColor)
            descriptor.locationText?.takeIf { it.isNotBlank() }?.let { loc ->
                Spacer(Modifier.height(4.dp))
                IconRow(icon = Icons.Default.Place, text = loc, contentColor = contentColor)
            }
            descriptor.meetingUrl?.takeIf { it.isNotBlank() }?.let { url ->
                Spacer(Modifier.height(4.dp))
                IconRow(icon = Icons.Default.Videocam, text = url, contentColor = contentColor)
            }
        }
    }
}

@Composable
private fun DateChip(local: LocalDateTime, contentColor: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .size(width = 56.dp, height = 64.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = local.month.name.take(3),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = local.dayOfMonth.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun TimeRow(
    startLocal: LocalDateTime,
    endLocal: LocalDateTime?,
    timezone: String,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    val timeText = buildString {
        append(startLocal.dayOfWeek.name.take(3))
        append(" · ")
        append(formatTime(startLocal))
        if (endLocal != null) {
            append("–")
            append(formatTime(endLocal))
        }
        append(' ')
        append(shortZone(timezone))
    }
    IconRow(icon = Icons.Default.Schedule, text = timeText, contentColor = contentColor)
}

@Composable
private fun IconRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UnparseableEventBubble(
    modifier: Modifier,
    contentColor: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Event,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(MR.string.chat_event_unparseable),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor.copy(alpha = 0.85f),
        )
    }
}

private fun formatTime(local: LocalDateTime): String {
    val h = local.hour.toString().padStart(2, '0')
    val m = local.minute.toString().padStart(2, '0')
    return "$h:$m"
}

/** Best-effort short zone label. "Europe/Copenhagen" -> "Copenhagen", "UTC" -> "UTC". */
private fun shortZone(tz: String): String {
    val slash = tz.lastIndexOf('/')
    val tail = if (slash >= 0) tz.substring(slash + 1) else tz
    return tail.replace('_', ' ')
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
