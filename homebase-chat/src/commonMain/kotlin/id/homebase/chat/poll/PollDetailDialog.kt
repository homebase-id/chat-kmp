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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.chat.services.convo.contact.ContactService
import kotlinx.coroutines.launch
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.util.initials
import id.homebase.core.widget.EmojiReaction
import id.homebase.resources.MR
import id.homebase.resources.chat_poll_details_title
import id.homebase.resources.chat_poll_end
import id.homebase.resources.chat_poll_end_failed
import id.homebase.resources.chat_poll_no_votes
import id.homebase.resources.chat_poll_one_vote
import id.homebase.resources.chat_poll_question_section
import id.homebase.resources.chat_poll_vote_count
import id.homebase.resources.chat_poll_you
import id.homebase.resources.close
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Fullscreen read-only roster for a Poll message. Shows the question in a tonal
 * box, then per-option sections with a count and the voter roster (avatar + name,
 * "You" for the current user). Only the organizer can end an open poll via the
 * End poll button. Votes are NOT editable here — voting is done in [PollBubble].
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
    onDismiss: () -> Unit,
) {
    val actionService: ChatMessageActionService = koinInject()
    val contactService: ContactService = koinInject()
    val ownerSession: OwnerSessionRepository = koinInject()
    val sender: ChatMessageSenderService = koinInject()
    val messageStream: ChatMessageStream = koinInject()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val selfOdinId = ownerSession.user.value?.odinId

    // Roster fetch — fires once per messageId. null = loading, empty = no reactions yet.
    val rosterReactions: List<EmojiReaction>? by produceState<List<EmojiReaction>?>(
        initialValue = null,
        key1 = messageId,
    ) {
        value = runCatching { actionService.getReactions(messageId) }.getOrDefault(emptyList())
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

            // Per-option sections
            descriptor.options.forEachIndexed { i, optionLabel ->
                PollOptionSection(
                    index = i,
                    label = optionLabel,
                    reactions = rosterReactions ?: emptyList(),
                    contactService = contactService,
                    selfOdinId = selfOdinId,
                )
                Spacer(Modifier.height(20.dp))
            }

            // End poll button — only for the organizer of an open poll.
            if (showEndButton) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
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

@Composable
private fun PollOptionSection(
    index: Int,
    label: String,
    reactions: List<EmojiReaction>,
    contactService: ContactService,
    selfOdinId: OdinId?,
) {
    val voters = reactions.filter { PollVote.optionOf(it.emoji) == index }
    val n = voters.size

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
        // Self first, then others — consistent with EventDetailDialog roster ordering intent.
        val sorted = voters.sortedByDescending { it.odinId == selfOdinId }
        Column {
            sorted.forEach { reactor ->
                PollVoterRow(
                    odinId = reactor.odinId,
                    isOwner = reactor.odinId == selfOdinId,
                    youLabel = youLabel,
                    contactService = contactService,
                )
            }
        }
    }
}

@Composable
private fun PollVoterRow(
    odinId: OdinId,
    isOwner: Boolean,
    youLabel: String,
    contactService: ContactService,
) {
    val resolvedName = contactService.resolveByOdinId(odinId)?.name ?: odinId.domainName
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
