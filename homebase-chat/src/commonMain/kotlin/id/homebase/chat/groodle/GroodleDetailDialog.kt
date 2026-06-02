package id.homebase.chat.groodle

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
import androidx.compose.material.icons.filled.LockClock
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.resources.MR
import id.homebase.resources.chat_event_organized_by
import id.homebase.resources.chat_groodle_closed
import id.homebase.resources.chat_groodle_tally
import id.homebase.resources.chat_groodle_vote_maybe
import id.homebase.resources.chat_groodle_vote_no
import id.homebase.resources.chat_groodle_vote_yes
import id.homebase.resources.chat_groodle_winner
import id.homebase.resources.chat_groodle_scheduled_in_zone
import id.homebase.resources.menu_back
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Fullscreen detail view for a Groodle. This is the **only** place the tally is
 * shown — the in-stream bubble stays results-free. Provides:
 *  - hero (title, description, organizer, deadline state)
 *  - one row per candidate slot, in the viewer's local zone (with a "Scheduled
 *    in <zone>" tagline when the authoring zone differs), the per-slot Y/M/N
 *    tally, and Yes / Maybe / No vote buttons reflecting the viewer's choice
 *  - the leading slot highlighted by score (Y=2, M=1, N=0)
 *
 * Voting writes ordinary chat reactions ([GroodleVote] codes). Tapping a
 * different choice clears the user's prior vote for that slot first (clear-then-
 * set), so the two can't coexist. Past the deadline the buttons lock.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun GroodleDetailDialog(
    descriptor: GroodleDescriptor,
    messageId: Uuid,
    conversationId: Uuid,
    ownReactions: ImmutableList<String>,
    reactionSummary: ReactionSummary?,
    organizer: OdinId?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GroodleDetailContent(
            descriptor = descriptor,
            messageId = messageId,
            conversationId = conversationId,
            ownReactions = ownReactions,
            reactionSummary = reactionSummary,
            organizer = organizer,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun GroodleDetailContent(
    descriptor: GroodleDescriptor,
    messageId: Uuid,
    conversationId: Uuid,
    ownReactions: ImmutableList<String>,
    reactionSummary: ReactionSummary?,
    organizer: OdinId?,
    onDismiss: () -> Unit,
) {
    val actionService: ChatMessageActionService = koinInject()
    val contactService: ContactService = koinInject()
    val scope = rememberCoroutineScope()

    val slotCount = descriptor.slots.size
    val myVotes = remember(ownReactions, slotCount, descriptor.allowMaybe) {
        GroodleVote.myVotes(ownReactions, slotCount, descriptor.allowMaybe)
    }
    val counts = remember(reactionSummary, slotCount, descriptor.allowMaybe) {
        GroodleVote.counts(reactionSummary, slotCount, descriptor.allowMaybe)
    }
    // 1-based index of the leading slot, or null when nobody has voted yet.
    val leadingSlot = remember(counts) {
        counts.entries
            .filter { it.value.score > 0 }
            .maxByOrNull { it.value.score }
            ?.key
    }

    // Deadline lock, ticked once on entry (re-evaluated on recomposition is fine
    // — the dialog is short-lived).
    val nowMs by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    val isClosed = descriptor.deadlineUtcMs != null && nowMs >= descriptor.deadlineUtcMs

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
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = descriptor.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            organizer?.let { host ->
                Spacer(Modifier.height(4.dp))
                val resolved = contactService.resolveByOdinId(host)?.name ?: host.domainName
                Text(
                    text = stringResource(MR.string.chat_event_organized_by) + " " + resolved,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (descriptor.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = descriptor.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (isClosed) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LockClock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(MR.string.chat_groodle_closed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            descriptor.slots.forEachIndexed { i, slot ->
                val slotIndex = i + 1
                SlotRow(
                    slot = slot,
                    authoredZoneId = descriptor.timezone,
                    counts = counts[slotIndex] ?: GroodleVote.SlotCounts(0, 0, 0),
                    myChoice = myVotes[slotIndex],
                    allowMaybe = descriptor.allowMaybe,
                    isLeading = leadingSlot == slotIndex,
                    isClosed = isClosed,
                    onVote = { choice ->
                        scope.launch {
                            applyVote(actionService, conversationId, messageId, myVotes[slotIndex], slotIndex, choice)
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SlotRow(
    slot: GroodleSlot,
    authoredZoneId: String,
    counts: GroodleVote.SlotCounts,
    myChoice: GroodleVote.Choice?,
    allowMaybe: Boolean,
    isLeading: Boolean,
    isClosed: Boolean,
    onVote: (GroodleVote.Choice) -> Unit,
) {
    val times = rememberSlotTimes(slot, authoredZoneId)
    val container = if (isLeading) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (isLeading) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
        color = container,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = slotHeadline(times),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                    modifier = Modifier.weight(1f),
                )
                if (isLeading) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(MR.string.chat_groodle_winner),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            if (times.authoredStartLocal != null && times.authoredTzId != null) {
                Spacer(Modifier.height(2.dp))
                val authoredDayTime = "${times.authoredStartLocal.dayOfWeek.name.take(3)} " +
                    formatHourMinute(times.authoredStartLocal)
                val authoredZoneShort = times.authoredTzId.substringAfterLast('/').replace('_', ' ')
                Text(
                    text = stringResource(MR.string.chat_groodle_scheduled_in_zone, authoredDayTime, authoredZoneShort),
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(MR.string.chat_groodle_tally, counts.yes, counts.maybe, counts.no),
                style = MaterialTheme.typography.labelMedium,
                color = onContainer.copy(alpha = 0.8f),
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoteChip(
                    label = stringResource(MR.string.chat_groodle_vote_yes),
                    selected = myChoice == GroodleVote.Choice.YES,
                    enabled = !isClosed,
                    onClick = { onVote(GroodleVote.Choice.YES) },
                    modifier = Modifier.weight(1f),
                )
                if (allowMaybe) {
                    VoteChip(
                        label = stringResource(MR.string.chat_groodle_vote_maybe),
                        selected = myChoice == GroodleVote.Choice.MAYBE,
                        enabled = !isClosed,
                        onClick = { onVote(GroodleVote.Choice.MAYBE) },
                        modifier = Modifier.weight(1f),
                    )
                }
                VoteChip(
                    label = stringResource(MR.string.chat_groodle_vote_no),
                    selected = myChoice == GroodleVote.Choice.NO,
                    enabled = !isClosed,
                    onClick = { onVote(GroodleVote.Choice.NO) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VoteChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        color = if (enabled || selected) container else container.copy(alpha = 0.5f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = content,
            )
        }
    }
}

/** "Thu · May 14 · 14:00–15:00" in the viewer's local zone. */
private fun slotHeadline(times: GroodleSlotTimes): String {
    val start = times.viewerStartLocal
    val dow = start.dayOfWeek.name.take(3)
    val date = "${start.month.name.take(3)} ${start.day}"
    val startTime = formatHourMinute(start)
    val end = times.viewerEndLocal
    return if (end == null) {
        "$dow · $date · $startTime"
    } else {
        "$dow · $date · $startTime–${formatHourMinute(end)}"
    }
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun applyVote(
    actionService: ChatMessageActionService,
    conversationId: Uuid,
    messageId: Uuid,
    currentChoice: GroodleVote.Choice?,
    slotIndex: Int,
    newChoice: GroodleVote.Choice,
) {
    val newCode = GroodleVote.encode(slotIndex, newChoice)
    if (currentChoice == newChoice) {
        // Same choice tapped twice → toggle off (clear the vote).
        actionService.toggleReaction(conversationId, messageId, newCode)
        return
    }
    if (currentChoice != null) {
        // Switch choice: clear the prior vote for this slot first.
        actionService.toggleReaction(conversationId, messageId, GroodleVote.encode(slotIndex, currentChoice))
    }
    actionService.toggleReaction(conversationId, messageId, newCode)
}
