package id.homebase.chat.event

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.resources.MR
import id.homebase.resources.chat_event_live_now
import id.homebase.resources.chat_event_past
import id.homebase.resources.chat_event_starting_soon
import id.homebase.resources.chat_event_unparseable
import id.homebase.resources.chat_event_your_time_in_zone
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * In-stream bubble for an Event message. Renders entirely from data already on
 * the message header — no payload fetch on scroll.
 *
 * Tap-to-open detail is self-contained: when [messageId] and [conversationId]
 * are non-null the bubble opens its own [EventDetailDialog]. When they're null
 * (composer preview, fallback paths) the bubble is read-only.
 *
 * @param descriptor Parsed event payload, or null when the descriptor failed to
 *   parse (older client / schema drift). When null we render a minimal fallback
 *   chip so the message doesn't disappear from the stream.
 * @param messageId Set on stream rendering — enables tap-to-detail + RSVP.
 * @param conversationId Set on stream rendering — needed for RSVP recipients.
 * @param ownReactions The user's existing reactions (from
 *   [id.homebase.chat.data.MessageUiModel.ownReactions]). Used by the detail
 *   dialog to highlight the user's current RSVP and to clear it before
 *   recording a new one.
 * @param reactionSummary Aggregate reactions across all reactors — feeds the
 *   detail dialog's RSVP rollup line.
 * @param contentColor Color for foreground text/icons (primary surface text).
 * @param containerColor Background color for the card (typically a Material 3
 *   surface variant).
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalFoundationApi::class)
@Composable
fun EventBubble(
    descriptor: EventDescriptor?,
    modifier: Modifier = Modifier,
    messageId: Uuid? = null,
    conversationId: Uuid? = null,
    ownReactions: ImmutableList<String> = persistentListOf(),
    reactionSummary: ReactionSummary? = null,
    organizer: OdinId? = null,
    onLongClick: (() -> Unit)? = null,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    if (descriptor == null) {
        UnparseableEventBubble(modifier = modifier, contentColor = contentColor, containerColor = containerColor)
        return
    }

    var showDetail by remember(messageId) { mutableStateOf(false) }
    val canOpenDetail = messageId != null && conversationId != null

    val tz = remember(descriptor.timezone) {
        runCatching { TimeZone.of(descriptor.timezone) }.getOrElse { TimeZone.currentSystemDefault() }
    }
    val startLocal = remember(descriptor.startUtcMs, tz) {
        Instant.fromEpochMilliseconds(descriptor.startUtcMs).toLocalDateTime(tz)
    }
    val endLocal = remember(descriptor.endUtcMs, tz) {
        descriptor.endUtcMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz) }
    }

    // 30-second ticker drives the live/starting-soon/past pill. Recomposes
    // only the pill subtree (the title and time rows are stable).
    val nowMs = remember(descriptor.startUtcMs) { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    val endMs = descriptor.endUtcMs ?: (descriptor.startUtcMs + 60L * 60L * 1000L)
    val phase = liveStatus(nowMs.longValue, descriptor.startUtcMs, endMs)
    LaunchedEffect(descriptor.startUtcMs, descriptor.endUtcMs) {
        // Stop ticking once the event is well in the past — past stays past.
        while (nowMs.longValue < endMs + 60L * 60L * 1000L) {
            delay(30.seconds)
            nowMs.longValue = Clock.System.now().toEpochMilliseconds()
        }
    }

    val isPast = phase is LiveStatus.Past
    val baseModifier = modifier
        .widthIn(min = 240.dp, max = 320.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(containerColor)
        .let {
            // combinedClickable (not clickable) so the parent message bubble's
            // own combinedClickable.onLongClick still fires on mobile —
            // Modifier.clickable consumes pointer events and otherwise swallows
            // the long-press, leaving Events with no action menu on mobile.
            if (canOpenDetail || onLongClick != null) {
                it.combinedClickable(
                    onClick = { if (canOpenDetail) showDetail = true },
                    onLongClick = onLongClick,
                )
            } else it
        }
        .padding(12.dp)
    val effectiveContent = if (isPast) contentColor.copy(alpha = 0.55f) else contentColor

    Row(modifier = baseModifier, verticalAlignment = Alignment.Top) {
        DateChip(startLocal, effectiveContent)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = descriptor.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = effectiveContent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                LiveStatusPill(phase = phase)
            }
            Spacer(Modifier.height(4.dp))
            TimeRow(startLocal = startLocal, endLocal = endLocal, timezone = descriptor.timezone, contentColor = effectiveContent)
            // When the event's IANA zone differs from the device zone, show the
            // device-local time on a second line so cross-zone events read at
            // a glance ("Tue 18:00 CET (your time: Tue 12:00 EST)").
            val deviceTz = remember { TimeZone.currentSystemDefault() }
            if (deviceTz.id != tz.id) {
                val deviceLocal = remember(descriptor.startUtcMs, deviceTz) {
                    Instant.fromEpochMilliseconds(descriptor.startUtcMs).toLocalDateTime(deviceTz)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        MR.string.chat_event_your_time_in_zone,
                        formatTime(deviceLocal),
                        shortZone(deviceTz.id),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = effectiveContent.copy(alpha = 0.6f),
                )
            }
            descriptor.locationText?.takeIf { it.isNotBlank() }?.let { loc ->
                Spacer(Modifier.height(4.dp))
                IconRow(icon = Icons.Default.Place, text = loc, contentColor = effectiveContent)
            }
            descriptor.meetingUrl?.takeIf { it.isNotBlank() }?.let { url ->
                Spacer(Modifier.height(4.dp))
                IconRow(icon = Icons.Default.Videocam, text = url, contentColor = effectiveContent)
            }
        }
    }

    if (showDetail && messageId != null && conversationId != null) {
        EventDetailDialog(
            descriptor = descriptor,
            messageId = messageId,
            conversationId = conversationId,
            ownReactions = ownReactions,
            counts = remember(reactionSummary) { EventRsvp.counts(reactionSummary) },
            onDismiss = { showDetail = false },
            organizer = organizer,
        )
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
            text = local.day.toString(),
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

/**
 * Time-relative status used to render the live/upcoming/past pill on the bubble.
 * Computed off the 30-second ticker. "StartingSoon" only triggers within the
 * hour before start; outside that window it's just Upcoming (no pill).
 */
private sealed interface LiveStatus {
    data object Upcoming : LiveStatus
    data class StartingSoon(val minutesAway: Int) : LiveStatus
    data object Live : LiveStatus
    data object Past : LiveStatus
}

private fun liveStatus(nowMs: Long, startMs: Long, endMs: Long): LiveStatus {
    val msUntilStart = startMs - nowMs
    return when {
        nowMs < startMs - 60L * 60L * 1000L -> LiveStatus.Upcoming
        nowMs < startMs -> LiveStatus.StartingSoon(((msUntilStart + 30_000L) / 60_000L).toInt().coerceAtLeast(1))
        nowMs <= endMs -> LiveStatus.Live
        else -> LiveStatus.Past
    }
}

@Composable
private fun LiveStatusPill(phase: LiveStatus) {
    if (phase is LiveStatus.Upcoming) return
    val text: String = when (phase) {
        is LiveStatus.StartingSoon -> stringResource(MR.string.chat_event_starting_soon, "${phase.minutesAway} min")
        LiveStatus.Live -> stringResource(MR.string.chat_event_live_now)
        LiveStatus.Past -> stringResource(MR.string.chat_event_past)
        LiveStatus.Upcoming -> return // unreachable, satisfies exhaustiveness
    }
    val container = when (phase) {
        LiveStatus.Live -> MaterialTheme.colorScheme.errorContainer
        is LiveStatus.StartingSoon -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (phase) {
        LiveStatus.Live -> MaterialTheme.colorScheme.onErrorContainer
        is LiveStatus.StartingSoon -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            fontWeight = FontWeight.SemiBold,
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

