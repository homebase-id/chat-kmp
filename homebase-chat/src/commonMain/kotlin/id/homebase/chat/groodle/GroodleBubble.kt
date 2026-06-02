package id.homebase.chat.groodle

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.LockClock
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.resources.MR
import id.homebase.resources.chat_groodle_closed
import id.homebase.resources.chat_groodle_closes_in
import id.homebase.resources.chat_groodle_option_count
import id.homebase.resources.chat_groodle_option_count_one
import id.homebase.resources.chat_groodle_tap_to_vote
import id.homebase.resources.chat_groodle_unparseable
import id.homebase.resources.chat_groodle_you_voted
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * In-stream bubble for a Groodle message. Renders entirely from the message
 * header — no payload fetch on scroll, and **no tally**: results live only in the
 * fullscreen [GroodleDetailDialog]. The bubble is a compact card (title,
 * description, option count, deadline state) plus a "you've voted / tap to vote"
 * hint derived from the viewer's own reactions.
 *
 * Tap-to-open is self-contained: when [messageId] and [conversationId] are
 * non-null the bubble opens its own detail dialog. When null (composer preview,
 * fallbacks) the bubble is read-only.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalFoundationApi::class)
@Composable
fun GroodleBubble(
    descriptor: GroodleDescriptor?,
    modifier: Modifier = Modifier,
    messageId: Uuid? = null,
    conversationId: Uuid? = null,
    ownReactions: ImmutableList<String> = persistentListOf(),
    reactionSummary: ReactionSummary? = null,
    organizer: OdinId? = null,
    onLongClick: (() -> Unit)? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    if (descriptor == null) {
        UnparseableGroodleBubble(modifier = modifier, contentColor = contentColor, containerColor = containerColor)
        return
    }

    var showDetail by remember(messageId) { mutableStateOf(false) }
    val canOpenDetail = messageId != null && conversationId != null

    // 30s ticker so the deadline state flips to "closed" live without a refresh.
    val nowMs = remember(descriptor.deadlineUtcMs) {
        mutableLongStateOf(Clock.System.now().toEpochMilliseconds())
    }
    val deadline = descriptor.deadlineUtcMs
    LaunchedEffect(descriptor.deadlineUtcMs) {
        if (deadline != null) {
            while (nowMs.longValue < deadline) {
                delay(30.seconds)
                nowMs.longValue = Clock.System.now().toEpochMilliseconds()
            }
        }
    }
    val isClosed = deadline != null && nowMs.longValue >= deadline

    val hasVoted = remember(ownReactions, descriptor) {
        GroodleVote.myVotes(ownReactions, descriptor.slots.size, descriptor.allowMaybe).isNotEmpty()
    }

    val baseModifier = modifier
        .widthIn(min = 240.dp, max = 320.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(containerColor)
        .let {
            if (canOpenDetail || onLongClick != null) {
                it.combinedClickable(
                    onClick = { if (canOpenDetail) showDetail = true },
                    onLongClick = onLongClick,
                )
            } else it
        }
        .padding(12.dp)

    Row(modifier = baseModifier, verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.85f),
            modifier = Modifier.size(28.dp).padding(top = 2.dp),
        )
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
            if (descriptor.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = descriptor.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            val optionsLabel = if (descriptor.slots.size == 1) {
                stringResource(MR.string.chat_groodle_option_count_one)
            } else {
                stringResource(MR.string.chat_groodle_option_count, descriptor.slots.size)
            }
            Text(
                text = optionsLabel,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.7f),
            )
            if (deadline != null) {
                Spacer(Modifier.height(2.dp))
                val deadlineText = if (isClosed) {
                    stringResource(MR.string.chat_groodle_closed)
                } else {
                    stringResource(MR.string.chat_groodle_closes_in, rememberDeadlineLabel(deadline))
                }
                Text(
                    text = deadlineText,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(8.dp))
            VotedHint(hasVoted = hasVoted, isClosed = isClosed, contentColor = contentColor)
        }
    }

    if (showDetail && messageId != null && conversationId != null) {
        GroodleDetailDialog(
            descriptor = descriptor,
            messageId = messageId,
            conversationId = conversationId,
            ownReactions = ownReactions,
            reactionSummary = reactionSummary,
            organizer = organizer,
            onDismiss = { showDetail = false },
        )
    }
}

@Composable
private fun VotedHint(hasVoted: Boolean, isClosed: Boolean, contentColor: Color) {
    val icon = if (hasVoted || isClosed) Icons.Default.CheckCircle else Icons.Default.HowToVote
    val tint = if (hasVoted) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.7f)
    val label = when {
        hasVoted -> stringResource(MR.string.chat_groodle_you_voted)
        isClosed -> stringResource(MR.string.chat_groodle_closed)
        else -> stringResource(MR.string.chat_groodle_tap_to_vote)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isClosed && !hasVoted) Icons.Default.LockClock else icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (hasVoted) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}

@Composable
private fun UnparseableGroodleBubble(
    modifier: Modifier,
    contentColor: Color,
    containerColor: Color,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(MR.string.chat_groodle_unparseable),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor.copy(alpha = 0.85f),
        )
    }
}

/** Absolute deadline in the viewer's local zone, e.g. "May 14 · 14:00". */
@Composable
private fun rememberDeadlineLabel(deadlineUtcMs: Long): String {
    val local = rememberViewerLocalDateTime(deadlineUtcMs)
    return "${local.month.name.take(3)} ${local.day} · ${formatHourMinute(local)}"
}

@Composable
private fun rememberViewerLocalDateTime(utcMs: Long): LocalDateTime {
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    return remember(utcMs, tz) {
        kotlin.time.Instant.fromEpochMilliseconds(utcMs).toLocalDateTime(tz)
    }
}
