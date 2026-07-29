package id.homebase.chat.poll

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.chat.services.convo.contact.ContactService
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.util.initials
import id.homebase.core.widget.EmojiReaction
import id.homebase.resources.MR
import id.homebase.resources.action_retry
import id.homebase.resources.chat_poll_details_title
import id.homebase.resources.chat_poll_end
import id.homebase.resources.chat_poll_end_failed
import id.homebase.resources.chat_poll_no_votes
import id.homebase.resources.chat_poll_one_vote
import id.homebase.resources.chat_poll_question_section
import id.homebase.resources.chat_poll_roster_error
import id.homebase.resources.chat_poll_roster_loading
import id.homebase.resources.chat_poll_roster_partial
import id.homebase.resources.chat_poll_vote_count
import id.homebase.resources.chat_poll_you
import id.homebase.resources.close
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Load state of the per-user voter roster. Loading and failure are distinct
 *  states on purpose: rendering either of them as "No votes" (which is what
 *  `runCatching{}.getOrDefault(emptyList())` did) is a wrong answer, not a
 *  missing one — see #1178. */
private sealed interface RosterState {
    data object Loading : RosterState
    data class Loaded(val reactions: List<EmojiReaction>) : RosterState
    data class Failed(val error: Throwable) : RosterState
}

/**
 * Fullscreen read-only roster for a Poll message. Shows the question in a tonal
 * box, then per-option sections with a count and the voter roster (avatar + name,
 * "You" for the current user). Only the organizer can end an open poll via the
 * End poll button. Votes are NOT editable here — voting is done in [PollBubble].
 *
 * **Two sources, deliberately.** The per-option COUNT comes from [reactionSummary],
 * the header `reactionPreview` the bubble itself counts from, so this screen can
 * never report fewer votes than the bubble behind it. The voter LIST comes from a
 * live `getReactions` read of the server's per-file reaction table, which is a
 * different store and can be short (see `ChatMessageActionService.getReactions`).
 * [PollVote.tally] merges them and flags the shortfall; a partial roster is
 * footnoted rather than silently under-reported.
 *
 * Mirrors [id.homebase.chat.event.EventDetailDialog]'s roster load pattern
 * (`produceState` + `ContactService`) and
 * [id.homebase.chat.groodle.GroodleDetailDialog]'s full-screen Dialog structure.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun PollDetailDialog(
    descriptor: PollDescriptor,
    messageId: Uuid,
    conversationId: Uuid,
    organizer: OdinId?,
    onDismiss: () -> Unit,
    // The viewer's own votes — keys the roster re-fetch so tallies refresh when the
    // viewer votes while the dialog is open (mirrors EventDetailDialog).
    ownReactions: ImmutableList<String> = persistentListOf(),
    // Authoritative per-option counts — the same header summary PollBubble renders.
    reactionSummary: ReactionSummary? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        PollDetailContent(
            descriptor = descriptor,
            messageId = messageId,
            conversationId = conversationId,
            organizer = organizer,
            ownReactions = ownReactions,
            reactionSummary = reactionSummary,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun PollDetailContent(
    descriptor: PollDescriptor,
    messageId: Uuid,
    conversationId: Uuid,
    organizer: OdinId?,
    ownReactions: ImmutableList<String>,
    reactionSummary: ReactionSummary?,
    onDismiss: () -> Unit,
) {
    val actionService: ChatMessageActionService = koinInject()
    val contactService: ContactService = koinInject()
    val ownerSession: OwnerSessionRepository = koinInject()
    val sender: ChatMessageSenderService = koinInject()
    val messageStream: ChatMessageStream = koinInject()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // Guards the End-poll button against a double-tap firing two updateMessage +
    // two status-line sends before onDismiss closes the dialog.
    var isEnding by remember { mutableStateOf(false) }

    val selfOdinId = ownerSession.user.value?.odinId

    // Roster fetch — re-fires when messageId OR the viewer's own votes change (so a
    // vote cast while the dialog is open refreshes the list), and when the user taps
    // Retry after a failure. A throw here is a real failure and is surfaced as one;
    // it used to be flattened into an empty list and rendered as "No votes".
    var retryToken by remember(messageId) { mutableStateOf(0) }
    val rosterState: RosterState by produceState<RosterState>(
        initialValue = RosterState.Loading,
        key1 = messageId,
        key2 = ownReactions,
        key3 = retryToken,
    ) {
        value = RosterState.Loading
        value = runCatching { actionService.getReactions(messageId) }
            .fold(
                onSuccess = { RosterState.Loaded(it) },
                onFailure = { t ->
                    Logger.w(tag = "PollDetailDialog", throwable = t) {
                        "Roster load failed for messageId=$messageId"
                    }
                    RosterState.Failed(t)
                },
            )
    }

    val roster = (rosterState as? RosterState.Loaded)?.reactions
    val tallies = remember(reactionSummary, roster, descriptor.options.size, selfOdinId) {
        PollVote.tally(
            summary = reactionSummary,
            roster = roster,
            optionCount = descriptor.options.size,
            selfOdinId = selfOdinId,
        )
    }

    val showEndButton = organizer != null && selfOdinId != null &&
        organizer == selfOdinId && !descriptor.closed

    val detailsTitleText = stringResource(MR.string.chat_poll_details_title)
    val endPollText = stringResource(MR.string.chat_poll_end)
    val endFailedMessage = stringResource(MR.string.chat_poll_end_failed)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detailsTitleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(MR.string.close),
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
            // Question section
            val questionSectionText = stringResource(MR.string.chat_poll_question_section)
            Text(
                text = questionSectionText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = descriptor.question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            // Roster status — only ever shown for the two states that are NOT
            // "nobody voted". A genuinely empty poll falls through to the
            // per-option "No votes" labels below.
            when (rosterState) {
                RosterState.Loading -> {
                    RosterLoadingRow()
                    Spacer(Modifier.height(16.dp))
                }

                is RosterState.Failed -> {
                    RosterErrorRow(onRetry = { retryToken++ })
                    Spacer(Modifier.height(16.dp))
                }

                is RosterState.Loaded -> Unit
            }

            // Per-option sections
            descriptor.options.forEachIndexed { i, optionLabel ->
                PollOptionSection(
                    label = optionLabel,
                    tally = tallies[i],
                    showPartialNote = rosterState !is RosterState.Loading,
                    contactService = contactService,
                    selfOdinId = selfOdinId,
                )
                Spacer(Modifier.height(20.dp))
            }

            // End poll button — only for the organizer of an open poll.
            if (showEndButton) {
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !isEnding,
                    onClick = onEndClick@{
                        if (isEnding) return@onEndClick
                        isEnding = true
                        scope.launch {
                            runCatching {
                                val live = messageStream.getMessage(messageId)
                                    ?: error("poll message not found: $messageId")
                                val closedDescriptor = descriptor.copy(closed = true)
                                sender.updateMessage(
                                    messageId = messageId,
                                    versionTag = live.versionTag,
                                    content = MessageContentParser.serialize(
                                        MessageContent.Poll(closedDescriptor)
                                    ),
                                )
                                sender.sendStatusMessage(
                                    messageUniqueId = Uuid.random(),
                                    conversationId = conversationId,
                                    statusMessage = StatusMessageData(
                                        statusMessage = StatusMessage.PollEnded,
                                        pollQuestion = descriptor.question,
                                        pollMessageId = messageId,
                                    ),
                                    previousMessageUniqueId = null,
                                    payloadBundle = null,
                                    additionalRecipients = emptyList(),
                                    recipientOverride = null,
                                )
                            }.onSuccess {
                                onDismiss()
                            }.onFailure { t ->
                                // Re-enable so the organizer can retry.
                                isEnding = false
                                Logger.w(tag = "PollDetailDialog", throwable = t) {
                                    "End poll failed for messageId=$messageId"
                                }
                                snackbarHostState.showSnackbar(endFailedMessage)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = endPollText)
                }
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Spinner + label shown while the voter roster is in flight. */
@Composable
private fun RosterLoadingRow() {
    val loadingText = stringResource(MR.string.chat_poll_roster_loading)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = loadingText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Error banner + Retry for a failed roster read. The per-option counts below
 *  still render from the header summary, so the screen degrades to "the numbers
 *  the bubble showed, minus the names" instead of claiming nobody voted. */
@Composable
private fun RosterErrorRow(onRetry: () -> Unit) {
    val errorText = stringResource(MR.string.chat_poll_roster_error)
    val retryText = stringResource(MR.string.action_retry)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(
                    text = retryText,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun PollOptionSection(
    label: String,
    tally: PollOptionTally,
    showPartialNote: Boolean,
    contactService: ContactService,
    selfOdinId: OdinId?,
) {
    val voters = tally.voters
    val n = tally.count

    // Count text built outside Text() — Konsist bars string literals in Text calls.
    val noVotesText = stringResource(MR.string.chat_poll_no_votes)
    val oneVoteText = stringResource(MR.string.chat_poll_one_vote)
    val countText = when {
        n == 0 -> noVotesText
        n == 1 -> oneVoteText
        else -> stringResource(MR.string.chat_poll_vote_count, n)
    }

    // Section header: option label + count
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (voters.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        val youLabel = stringResource(MR.string.chat_poll_you)
        // Already self-first — PollVote.tally owns the ordering.
        Column {
            voters.forEach { voter ->
                PollVoterRow(
                    odinId = voter,
                    isOwner = voter == selfOdinId,
                    youLabel = youLabel,
                    contactService = contactService,
                )
            }
        }
    }

    // The count came from the header summary; the names came from the per-file
    // reaction read. When the latter is short, say so instead of letting the two
    // silently disagree. Deliberately OUTSIDE the `voters.isNotEmpty()` block —
    // the #1178 case is "3 votes, zero names", which is exactly when this note
    // matters most. Suppressed while the roster is still loading, where a
    // shortfall says nothing yet.
    if (showPartialNote && tally.isPartial) {
        val partialText = stringResource(MR.string.chat_poll_roster_partial, voters.size, n)
        Text(
            text = partialText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PollVoterRow(
    odinId: OdinId,
    isOwner: Boolean,
    youLabel: String,
    contactService: ContactService,
) {
    val resolvedName = contactService.resolveByOdinId(odinId).name
    val avatarOptions = AvatarOptions(size = 32.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
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
        Text(
            text = if (isOwner) youLabel else resolvedName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
