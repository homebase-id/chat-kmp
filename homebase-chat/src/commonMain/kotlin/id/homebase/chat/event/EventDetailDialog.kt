package id.homebase.chat.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.resources.MR
import id.homebase.resources.chat_event_add_to_calendar
import id.homebase.resources.chat_event_join_meeting
import id.homebase.resources.chat_event_open_in_maps
import id.homebase.resources.chat_event_rsvp_maybe
import id.homebase.resources.chat_event_rsvp_no
import id.homebase.resources.chat_event_rsvp_summary
import id.homebase.resources.chat_event_rsvp_yes
import id.homebase.resources.cancel
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Fullscreen detail view for an event message. Provides:
 *  - hero (title, day-strip, full local time + IANA zone)
 *  - description
 *  - tap-throughs for location and meeting URL
 *  - "Add to calendar" (wired in slice 4)
 *  - RSVP via three reaction emojis ([EventRsvp.GOING] / [NOT_GOING] / [MAYBE]).
 *    Tapping a different RSVP clears the user's prior one first so the two
 *    can't coexist. Reactions are the source of truth — they sync, they
 *    aggregate, and they survive across devices for free.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun EventDetailDialog(
    descriptor: EventDescriptor,
    messageId: Uuid,
    conversationId: Uuid,
    ownReactions: Iterable<String>,
    counts: EventRsvp.Counts,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        EventDetailContent(
            descriptor = descriptor,
            messageId = messageId,
            conversationId = conversationId,
            ownReactions = ownReactions,
            counts = counts,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun EventDetailContent(
    descriptor: EventDescriptor,
    messageId: Uuid,
    conversationId: Uuid,
    ownReactions: Iterable<String>,
    counts: EventRsvp.Counts,
    onDismiss: () -> Unit,
) {
    val actionService: ChatMessageActionService = koinInject()
    val uriHandler = LocalUriHandler.current
    val calendarLauncher = rememberCalendarLauncher()
    val scope = rememberCoroutineScope()

    val tz = remember(descriptor.timezone) {
        runCatching { TimeZone.of(descriptor.timezone) }.getOrElse { TimeZone.currentSystemDefault() }
    }
    val startLocal = remember(descriptor.startUtcMs, tz) {
        Instant.fromEpochMilliseconds(descriptor.startUtcMs).toLocalDateTime(tz)
    }
    val endLocal = remember(descriptor.endUtcMs, tz) {
        descriptor.endUtcMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz) }
    }
    val currentRsvp = remember(ownReactions) { EventRsvp.currentRsvp(ownReactions) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            HeroHeader(descriptor = descriptor, startLocal = startLocal, endLocal = endLocal)

            if (descriptor.description.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = descriptor.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(16.dp))

            descriptor.locationText?.takeIf { it.isNotBlank() }?.let { loc ->
                ActionRow(
                    icon = Icons.Default.Place,
                    label = loc,
                    actionLabel = stringResource(MR.string.chat_event_open_in_maps),
                    onClick = {
                        val q = if (descriptor.lat != null && descriptor.lon != null) {
                            "${descriptor.lat},${descriptor.lon}"
                        } else {
                            urlEncode(loc)
                        }
                        runCatching { uriHandler.openUri("geo:$q?q=$q") }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            descriptor.meetingUrl?.takeIf { it.isNotBlank() }?.let { url ->
                ActionRow(
                    icon = Icons.Default.Videocam,
                    label = url,
                    actionLabel = stringResource(MR.string.chat_event_join_meeting),
                    onClick = { runCatching { uriHandler.openUri(url) } },
                )
                Spacer(Modifier.height(8.dp))
            }

            ActionRow(
                icon = Icons.Default.CalendarMonth,
                label = stringResource(MR.string.chat_event_add_to_calendar),
                actionLabel = null,
                onClick = { calendarLauncher.addToCalendar(descriptor) },
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(
                    MR.string.chat_event_rsvp_summary,
                    counts.going.toString(),
                    counts.notGoing.toString(),
                    counts.maybe.toString(),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RsvpButton(
                    emoji = EventRsvp.GOING,
                    label = stringResource(MR.string.chat_event_rsvp_yes),
                    selected = currentRsvp == EventRsvp.GOING,
                    onClick = { scope.launch { applyRsvp(actionService, conversationId, messageId, currentRsvp, EventRsvp.GOING) } },
                    modifier = Modifier.weight(1f),
                )
                RsvpButton(
                    emoji = EventRsvp.MAYBE,
                    label = stringResource(MR.string.chat_event_rsvp_maybe),
                    selected = currentRsvp == EventRsvp.MAYBE,
                    onClick = { scope.launch { applyRsvp(actionService, conversationId, messageId, currentRsvp, EventRsvp.MAYBE) } },
                    modifier = Modifier.weight(1f),
                )
                RsvpButton(
                    emoji = EventRsvp.NOT_GOING,
                    label = stringResource(MR.string.chat_event_rsvp_no),
                    selected = currentRsvp == EventRsvp.NOT_GOING,
                    onClick = { scope.launch { applyRsvp(actionService, conversationId, messageId, currentRsvp, EventRsvp.NOT_GOING) } },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun applyRsvp(
    actionService: ChatMessageActionService,
    conversationId: Uuid,
    messageId: Uuid,
    currentRsvp: String?,
    newRsvp: String,
) {
    // The reaction service uses toggle semantics (add if absent, remove if present).
    if (currentRsvp == newRsvp) {
        // Same button tapped twice → toggle off.
        actionService.toggleReaction(conversationId, messageId, newRsvp)
        return
    }
    if (currentRsvp != null) {
        // Switch RSVP: clear the prior one before recording the new.
        actionService.toggleReaction(conversationId, messageId, currentRsvp)
    }
    actionService.toggleReaction(conversationId, messageId, newRsvp)
}

@Composable
private fun HeroHeader(
    descriptor: EventDescriptor,
    startLocal: LocalDateTime,
    endLocal: LocalDateTime?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 80.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = startLocal.month.name.take(3),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = startLocal.day.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = descriptor.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatHero(startLocal, endLocal, descriptor.timezone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    actionLabel: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (actionLabel != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RsvpButton(
    emoji: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = container,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = onContainer,
            )
        }
    }
}

private fun formatHero(start: LocalDateTime, end: LocalDateTime?, tz: String): String {
    val dow = start.dayOfWeek.name.take(3)
    val sh = start.hour.toString().padStart(2, '0')
    val sm = start.minute.toString().padStart(2, '0')
    val tzShort = tz.substringAfterLast('/').replace('_', ' ')
    return if (end == null) {
        "$dow · $sh:$sm $tzShort"
    } else {
        val eh = end.hour.toString().padStart(2, '0')
        val em = end.minute.toString().padStart(2, '0')
        "$dow · $sh:$sm – $eh:$em $tzShort"
    }
}

private fun urlEncode(s: String): String =
    s.replace(" ", "%20").replace(",", "%2C")
