package id.homebase.chat.poll

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.resources.MR
import id.homebase.resources.chat_poll_no_votes
import id.homebase.resources.chat_poll_subtitle_closed
import id.homebase.resources.chat_poll_subtitle_multiple
import id.homebase.resources.chat_poll_subtitle_single
import id.homebase.resources.chat_poll_unparseable
import id.homebase.resources.chat_poll_view_results
import id.homebase.resources.chat_poll_view_votes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * In-stream bubble for a Poll message. Renders entirely from the message header
 * — no payload fetch on scroll. Votes are ordinary chat reactions (see [PollVote]).
 *
 * Open polls show option rows with leading radio/check indicators, vote counts,
 * and progress bars. Tapping a row votes (single-choice: clear-then-set;
 * multiple-choice: independent toggle). Closed polls are read-only with the
 * viewer's own picks shown as trailing checks.
 *
 * Tap-to-detail opens [PollDetailDialog] (full-screen read-only roster). The
 * footer button sets [showDetail] = true to host the dialog inline, mirroring
 * how [id.homebase.chat.event.EventBubble] self-hosts [id.homebase.chat.event.EventDetailDialog].
 *
 * Mirror of [id.homebase.chat.groodle.GroodleBubble] — colors, toggle logic, and
 * bubble structure are intentionally parallel.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalFoundationApi::class)
@Composable
fun PollBubble(
    descriptor: PollDescriptor?,
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
    if (descriptor == null || !descriptor.isValid()) {
        UnparseablePollBubble(
            modifier = modifier,
            contentColor = contentColor,
            containerColor = containerColor,
        )
        return
    }

    val optionCount = descriptor.options.size
    val counts = remember(reactionSummary, optionCount) {
        PollVote.counts(reactionSummary, optionCount)
    }
    val total = remember(counts) { counts.sum() }
    val maxCount = remember(counts) { counts.max().coerceAtLeast(1) }
    val own = remember(ownReactions, optionCount) {
        PollVote.ownVotes(ownReactions, optionCount)
    }

    // voteTarget is non-null only when voting is possible (open poll + both IDs present).
    // Lambdas check voteTarget != null rather than messageId/conversationId individually,
    // which avoids repeated nullable-check compiler warnings.
    val voteTarget: Pair<Uuid, Uuid>? =
        if (!descriptor.closed && messageId != null && conversationId != null) {
            messageId to conversationId
        } else null
    val canVote = voteTarget != null
    // Off-stream (action-menu preview, message info, reply quote) both ids are null: nothing here
    // may take a pointer, because a handler that can't act still eats the tap the action-menu
    // scrim needs to dismiss.
    val canOpenDetail = messageId != null && conversationId != null
    val actionService: ChatMessageActionService = koinInject()
    val scope = rememberCoroutineScope()

    var showDetail by remember(messageId) { mutableStateOf(false) }

    val baseModifier = modifier
        .widthIn(min = 240.dp, max = 320.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(containerColor)
        .let {
            if (canOpenDetail) {
                it.combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick,
                )
            } else it
        }
        .padding(12.dp)

    // Build subtitle strings outside composables to avoid interpolation string literals in Text calls.
    val subtitleText = when {
        descriptor.closed -> stringResource(MR.string.chat_poll_subtitle_closed)
        descriptor.allowMultiple -> stringResource(MR.string.chat_poll_subtitle_multiple)
        else -> stringResource(MR.string.chat_poll_subtitle_single)
    }
    val noVotesText = stringResource(MR.string.chat_poll_no_votes)
    val viewVotesText = stringResource(MR.string.chat_poll_view_votes)
    val viewResultsText = stringResource(MR.string.chat_poll_view_results)

    Column(modifier = baseModifier) {
        // Question — large headline, no leading icon.
        Text(
            text = descriptor.question,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        // Subtitle — pre-built string variable, not an inline interpolation literal.
        Text(
            text = subtitleText,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.65f),
        )

        Spacer(Modifier.height(10.dp))

        // Option rows
        descriptor.options.forEachIndexed { i, option ->
            val isOwn = i in own
            val count = counts[i]
            val progress = count.toFloat() / maxCount

            PollOptionRow(
                label = option,
                count = count,
                progress = progress,
                isOwn = isOwn,
                closed = descriptor.closed,
                canVote = canVote,
                contentColor = contentColor,
                onTap = {
                    val target = voteTarget
                    if (target != null) {
                        scope.launch {
                            applyPollVote(
                                actionService = actionService,
                                conversationId = target.second,
                                messageId = target.first,
                                optionIndex = i,
                                own = own,
                                allowMultiple = descriptor.allowMultiple,
                            )
                        }
                    }
                },
            )

            if (i < descriptor.options.size - 1) {
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        // Footer: no-votes hint OR view button
        if (total == 0 && !descriptor.closed) {
            Text(
                text = noVotesText,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            val footerLabel = if (descriptor.closed) viewResultsText else viewVotesText
            if (canOpenDetail) {
                TextButton(
                    onClick = { showDetail = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    PollFooterLabel(footerLabel)
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PollFooterLabel(footerLabel, MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showDetail && messageId != null && conversationId != null) {
        PollDetailDialog(
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
private fun PollFooterLabel(text: String, color: Color = Color.Unspecified) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

@Composable
private fun PollOptionRow(
    label: String,
    count: Int,
    progress: Float,
    isOwn: Boolean,
    closed: Boolean,
    canVote: Boolean,
    contentColor: Color,
    onTap: () -> Unit,
) {
    val rowModifier = if (canVote) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    }

    Column(modifier = rowModifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!closed) {
                // Open: leading indicator — filled check if voted, outline circle otherwise.
                val indicatorTint =
                    if (isOwn) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.45f)
                Icon(
                    imageVector = if (isOwn) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = indicatorTint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }

            // Option label — weight(1f) so the trailing count never wraps.
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                fontWeight = if (isOwn) FontWeight.SemiBold else FontWeight.Normal,
            )

            Spacer(Modifier.width(8.dp))

            if (closed && isOwn) {
                // Closed: trailing check for own pick.
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
            }

            // Per-option count — count.toString() assigned to a variable so the
            // Text call receives a variable reference, not an interpolation literal.
            val countText = count.toString()
            Text(
                text = countText,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(4.dp))

        // Progress bar — track + fill using Box so no external dependency is needed.
        // Winner fills fully (progress = count / max(1, max)); others proportionally.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.12f)),
        ) {
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOwn) MaterialTheme.colorScheme.primary
                            else contentColor.copy(alpha = 0.45f),
                        ),
                )
            }
        }
    }
}

@Composable
private fun UnparseablePollBubble(
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
            imageVector = Icons.Default.HowToVote,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(MR.string.chat_poll_unparseable),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor.copy(alpha = 0.85f),
        )
    }
}

/**
 * Applies a poll vote for [optionIndex]:
 * - [allowMultiple]: each option toggles independently.
 * - Single-choice: if a different option is currently voted, clear it first, then
 *   toggle the tapped option (clear-then-set). Tapping the already-chosen option
 *   toggles it off (clear only).
 *
 * Mirrors [id.homebase.chat.groodle.GroodleDetailDialog]'s `applyVote` logic.
 */
@OptIn(ExperimentalUuidApi::class)
private suspend fun applyPollVote(
    actionService: ChatMessageActionService,
    conversationId: Uuid,
    messageId: Uuid,
    optionIndex: Int,
    own: Set<Int>,
    allowMultiple: Boolean,
) {
    val newCode = PollVote.codeFor(optionIndex)
    if (allowMultiple) {
        // Each option is an independent toggle.
        actionService.toggleReaction(conversationId, messageId, newCode)
        return
    }
    // Single-choice: clear EVERY other currently-voted option before toggling the
    // tapped one. Iterating (not firstOrNull) defends against a stale `own` set that
    // somehow holds more than one prior vote, which would otherwise leave a ghost.
    own.filter { it != optionIndex }.forEach { prev ->
        actionService.toggleReaction(conversationId, messageId, PollVote.codeFor(prev))
    }
    // Toggle the tapped option (sets it if not voted, clears it if already voted).
    actionService.toggleReaction(conversationId, messageId, newCode)
}
