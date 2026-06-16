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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QuestionMark
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.common.OdinId
import kotlinx.collections.immutable.ImmutableList
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.util.initials
import id.homebase.core.widget.EmojiReaction
import id.homebase.resources.MR
import id.homebase.resources.you
import id.homebase.resources.chat_event_add_to_google_calendar
import id.homebase.resources.chat_event_copy_meeting_url
import id.homebase.resources.chat_event_download_ics
import id.homebase.resources.chat_event_join_meeting
import id.homebase.resources.chat_event_open_in_maps
import id.homebase.resources.chat_event_organized_by
import id.homebase.resources.chat_event_rsvp_maybe
import id.homebase.resources.chat_event_rsvp_no
import id.homebase.resources.chat_event_rsvp_summary
import id.homebase.resources.chat_event_rsvp_yes
import id.homebase.resources.chat_event_scheduled_in_zone
import id.homebase.resources.menu_back
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
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
    ownReactions: ImmutableList<String>,
    counts: EventRsvp.Counts,
    onDismiss: () -> Unit,
    organizer: OdinId? = null,
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
            organizer = organizer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun EventDetailContent(
    descriptor: EventDescriptor,
    messageId: Uuid,
    conversationId: Uuid,
    ownReactions: ImmutableList<String>,
    counts: EventRsvp.Counts,
    onDismiss: () -> Unit,
    organizer: OdinId?,
) {
    val actionService: ChatMessageActionService = koinInject()
    val contactService: ContactService = koinInject()
    val ownerSession: OwnerSessionRepository = koinInject()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val calendarLauncher = rememberCalendarLauncher()
    val scope = rememberCoroutineScope()

    val selfOdinId = ownerSession.user.value?.odinId

    // Roster fetch — fires once per messageId. Re-fetches when ownReactions
    // changes (so tapping an RSVP refreshes the list within ~1s without
    // closing/reopening). null = loading, empty list = no reactors yet.
    val rosterReactions: List<EmojiReaction>? by produceState<List<EmojiReaction>?>(
        initialValue = null,
        key1 = messageId,
        key2 = ownReactions,
    ) {
        value = runCatching { actionService.getReactions(messageId) }.getOrDefault(emptyList())
    }

    val times = rememberEventTimes(descriptor)
    val currentRsvp = remember(ownReactions) { EventRsvp.currentRsvp(ownReactions) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
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
            HeroHeader(descriptor = descriptor, times = times)

            organizer?.let { host ->
                Spacer(Modifier.height(12.dp))
                OrganizerRow(
                    odinId = host,
                    isOwner = host == selfOdinId,
                    youLabel = stringResource(MR.string.you),
                    organizedByLabel = stringResource(MR.string.chat_event_organized_by),
                    contactService = contactService,
                )
            }

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
                        // If the user typed a full URL, respect their intent — open it
                        // verbatim. Otherwise build a Google Maps query: prefer captured
                        // coordinates (drops a pin exactly), fall back to the encoded
                        // text (Google's fuzzy match handles "Starbucks Copenhagen
                        // Central" cleanly).
                        val href = when {
                            isLikelyUrl(loc) -> loc.trim()
                            descriptor.lat != null && descriptor.lon != null ->
                                "https://www.google.com/maps/search/?api=1&query=${descriptor.lat},${descriptor.lon}"
                            else ->
                                "https://www.google.com/maps/search/?api=1&query=${urlEncode(loc)}"
                        }
                        runCatching { uriHandler.openUri(href) }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            descriptor.meetingUrl?.takeIf { it.isNotBlank() }?.let { url ->
                val copyLabel = stringResource(MR.string.chat_event_copy_meeting_url)
                ActionRow(
                    icon = Icons.Default.Videocam,
                    label = url,
                    actionLabel = stringResource(MR.string.chat_event_join_meeting),
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    // Drag-select doesn't work on Desktop here — the row's
                    // .clickable Surface consumes mouse press before
                    // SelectionContainer can start a selection. Explicit copy
                    // button works the same on every platform and doesn't
                    // depend on selection gestures.
                    trailing = {
                        IconButton(
                            onClick = {
                                scope.launch { clipboard.setClipEntry(clipEntryOf(url)) }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = copyLabel,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            ActionRow(
                icon = Icons.Default.CalendarMonth,
                label = stringResource(MR.string.chat_event_add_to_google_calendar),
                actionLabel = null,
                onClick = { runCatching { uriHandler.openUri(googleCalendarUrl(descriptor)) } },
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                icon = Icons.Default.CalendarMonth,
                label = stringResource(MR.string.chat_event_download_ics),
                actionLabel = null,
                onClick = { calendarLauncher.addToCalendar(descriptor, messageId) },
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
                    icon = Icons.Default.Check,
                    label = stringResource(MR.string.chat_event_rsvp_yes),
                    selected = currentRsvp == EventRsvp.GOING,
                    onClick = { scope.launch { applyRsvp(actionService, conversationId, messageId, currentRsvp, EventRsvp.GOING) } },
                    modifier = Modifier.weight(1f),
                )
                RsvpButton(
                    icon = Icons.Default.QuestionMark,
                    label = stringResource(MR.string.chat_event_rsvp_maybe),
                    selected = currentRsvp == EventRsvp.MAYBE,
                    onClick = { scope.launch { applyRsvp(actionService, conversationId, messageId, currentRsvp, EventRsvp.MAYBE) } },
                    modifier = Modifier.weight(1f),
                )
                RsvpButton(
                    icon = Icons.Default.Close,
                    label = stringResource(MR.string.chat_event_rsvp_no),
                    selected = currentRsvp == EventRsvp.NOT_GOING,
                    onClick = { scope.launch { applyRsvp(actionService, conversationId, messageId, currentRsvp, EventRsvp.NOT_GOING) } },
                    modifier = Modifier.weight(1f),
                )
            }

            rosterReactions?.takeIf { it.isNotEmpty() }?.let { reactionsList ->
                Spacer(Modifier.height(24.dp))
                RsvpRoster(
                    reactions = reactionsList,
                    contactService = contactService,
                    selfOdinId = selfOdinId,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Three-section roster of reactors, grouped by RSVP emoji. Sections with no
 * reactors are omitted. Mirrors the avatar + name resolution that
 * `MessageInfoScreen` / `ReactionsBottomSheet` use, so the same identity
 * renders identically here and there.
 */
@Composable
private fun RsvpRoster(
    reactions: List<EmojiReaction>,
    contactService: ContactService,
    selfOdinId: OdinId?,
) {
    Column {
        RsvpSection(
            sectionLabel = stringResource(MR.string.chat_event_rsvp_yes),
            icon = Icons.Default.Check,
            reactors = reactions.filter { it.emoji == EventRsvp.GOING },
            contactService = contactService,
            selfOdinId = selfOdinId,
        )
        RsvpSection(
            sectionLabel = stringResource(MR.string.chat_event_rsvp_maybe),
            icon = Icons.Default.QuestionMark,
            reactors = reactions.filter { it.emoji == EventRsvp.MAYBE },
            contactService = contactService,
            selfOdinId = selfOdinId,
        )
        RsvpSection(
            sectionLabel = stringResource(MR.string.chat_event_rsvp_no),
            icon = Icons.Default.Close,
            reactors = reactions.filter { it.emoji == EventRsvp.NOT_GOING },
            contactService = contactService,
            selfOdinId = selfOdinId,
        )
    }
}

@Composable
private fun RsvpSection(
    sectionLabel: String,
    icon: ImageVector,
    reactors: List<EmojiReaction>,
    contactService: ContactService,
    selfOdinId: OdinId?,
) {
    if (reactors.isEmpty()) return
    val youLabel = stringResource(MR.string.you)
    // "<localized label> · <count>", with a small leading vector icon. The
    // pieces are already localized; this is a presentational concat, not a
    // translatable string.
    val headerText = sectionLabel + " · " + reactors.size.toString()
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = headerText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(4.dp))
    Column {
        reactors.forEach { reactor ->
            ReactorRow(
                odinId = reactor.odinId,
                isOwner = reactor.odinId == selfOdinId,
                youLabel = youLabel,
                contactService = contactService,
            )
        }
    }
}

@Composable
private fun OrganizerRow(
    odinId: OdinId,
    isOwner: Boolean,
    youLabel: String,
    organizedByLabel: String,
    contactService: ContactService,
) {
    val resolvedName = contactService.resolveByOdinId(odinId)?.name ?: odinId.domainName
    val avatarOptions = AvatarOptions(size = 32.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isOwner) {
            OwnerAvatar(
                odinId = odinId,
                profileImageData = null,
                initials = resolvedName.initials(),
                options = avatarOptions,
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
            )
        } else {
            PublicAvatar(
                odinId = odinId,
                initials = resolvedName.initials(),
                options = avatarOptions,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = organizedByLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (isOwner) youLabel else resolvedName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ReactorRow(
    odinId: OdinId,
    isOwner: Boolean,
    youLabel: String,
    contactService: ContactService,
) {
    // Same resolution path as MessageInfoViewModel.
    val resolvedName = contactService.resolveByOdinId(odinId)?.name ?: odinId.domainName
    val avatarOptions = AvatarOptions(size = 32.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isOwner) {
            // OwnerAvatar with profileImageData = null delegates to PublicAvatar
            // (https://$odinId/pub/image), matching the behaviour in
            // ReactionsBottomSheet.ReactionRow for the current user.
            OwnerAvatar(
                odinId = odinId,
                profileImageData = null,
                initials = resolvedName.initials(),
                options = avatarOptions,
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
            )
        } else {
            PublicAvatar(
                odinId = odinId,
                initials = resolvedName.initials(),
                options = avatarOptions,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (isOwner) youLabel else resolvedName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
    times: EventTimes,
) {
    val startLocal = times.viewerStartLocal
    val endLocal = times.viewerEndLocal
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
                    text = formatHero(startLocal, endLocal, times.viewerTz.id),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (times.authoredStartLocal != null && times.authoredTzId != null) {
                Spacer(Modifier.height(2.dp))
                val authoredDayTime = "${times.authoredStartLocal.dayOfWeek.name.take(3)} " +
                    times.authoredStartLocal.hour.toString().padStart(2, '0') + ":" +
                    times.authoredStartLocal.minute.toString().padStart(2, '0')
                val authoredZoneShort = times.authoredTzId.substringAfterLast('/').replace('_', ' ')
                Text(
                    text = stringResource(
                        MR.string.chat_event_scheduled_in_zone,
                        authoredDayTime,
                        authoredZoneShort,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
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
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            // Lighter end padding when there's a trailing slot — the IconButton
            // brings its own 48 dp touch target which already provides the
            // visual breathing room.
            modifier = Modifier.padding(
                start = 14.dp,
                end = if (trailing != null) 6.dp else 14.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
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
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun RsvpButton(
    icon: ImageVector,
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(4.dp))
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

private fun isLikelyUrl(s: String): Boolean {
    val t = s.trim().lowercase()
    return t.startsWith("http://") || t.startsWith("https://")
}
