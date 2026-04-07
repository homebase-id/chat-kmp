package id.homebase.chat.messageinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.widget.ReceivedMessageBubbleDisplayOnly
import id.homebase.chat.widget.SentMessageBubbleDisplayOnly
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.util.formateDateTime
import id.homebase.resources.MR
import id.homebase.resources.chat_message_info
import id.homebase.resources.delivered_to
import id.homebase.resources.details
import id.homebase.resources.failed
import id.homebase.resources.label_edited
import id.homebase.resources.label_received
import id.homebase.resources.label_sent
import id.homebase.resources.menu_back
import id.homebase.resources.reactions
import id.homebase.resources.read_by
import id.homebase.resources.sending_to
import id.homebase.resources.uploaded
import id.homebase.resources.unknown_status
import org.jetbrains.compose.resources.stringResource

@Composable
fun MessageInfoScreen(
    viewModel: MessageInfoViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    when (uiState.uiEvent) {
        is MessageInfoUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }

        null -> {}
    }

    MessageInfoUi(uiState = uiState, onUiAction = viewModel::onUiAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoUi(
    uiState: MessageInfoUiState,
    onUiAction: (MessageInfoUiAction) -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_message_info)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(MessageInfoUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        }) { padding ->
        Column(
            modifier = Modifier
                .consumeWindowInsets(padding)
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                uiState.message?.let { message ->
                    val isSentByYou = message.isAuthoredBy(uiState.ownerSession?.odinId)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = if (isSentByYou) Arrangement.End else Arrangement.Start
                    ) {
                        if (isSentByYou) {
                            SentMessageBubbleDisplayOnly(
                                message = message,
                            )
                        } else {
                            ReceivedMessageBubbleDisplayOnly(
                                message = message,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Details section
                SectionHeader(
                    text = stringResource(MR.string.details),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = stringResource(
                        MR.string.label_sent,
                        uiState.message?.userDate?.let { formateDateTime(it) } ?: "",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Text(
                    text = stringResource(
                        MR.string.label_received,
                        uiState.message?.created?.let { formateDateTime(it) } ?: "",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (uiState.message?.isEdited == true) {
                    Text(
                        text = stringResource(
                            MR.string.label_edited,
                            uiState.message.modified?.let { formateDateTime(it) } ?: "",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                // Recipients section grouped by status
                if (uiState.isTransferHistoryLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else if (uiState.recipients.isNotEmpty()) {
                    val grouped = remember(uiState.recipients) {
                        uiState.recipients.groupBy { it.deliveryStatus }
                    }

                    val statusOrder = listOf(
                        ChatDeliveryStatus.Sending,
                        ChatDeliveryStatus.Sent,
                        ChatDeliveryStatus.Delivered,
                        ChatDeliveryStatus.Read,
                        ChatDeliveryStatus.Failed,
                    )

                    // Render known statuses in defined order, then any unknown ones
                    val allStatuses = statusOrder + (grouped.keys - statusOrder.toSet())

                    allStatuses.forEach { status ->
                        val entries = grouped[status] ?: return@forEach
                        val label = when (status) {
                            ChatDeliveryStatus.Read -> stringResource(MR.string.read_by)
                            ChatDeliveryStatus.Delivered -> stringResource(MR.string.delivered_to)
                            ChatDeliveryStatus.Sent -> stringResource(MR.string.uploaded)
                            ChatDeliveryStatus.Sending -> stringResource(MR.string.sending_to)
                            ChatDeliveryStatus.Failed -> stringResource(MR.string.failed)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(
                            text = label,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        entries.forEach { recipient ->
                            RecipientRow(
                                recipient = recipient,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                // Reactions section
                if (uiState.isReactionsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else if (uiState.reactions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionHeader(
                        text = stringResource(MR.string.reactions),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    uiState.reactions.forEach { reaction ->
                        ReactionRow(
                            reaction = reaction,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .semantics { heading() },
        )
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun RecipientRow(recipient: RecipientStatusUiModel, modifier: Modifier = Modifier) {
    val odinId = remember(recipient.odinId) { OdinId(recipient.odinId) }
    val errorText = recipient.errorDetailRes?.let { stringResource(it) }
    val accessibilityDescription = buildString {
        append(recipient.displayName)
        if (errorText != null) {
            append(", ")
            append(errorText)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = accessibilityDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PublicAvatar(
            odinId = odinId,
            initials = recipient.displayName.firstOrNull()?.toString(),
            options = AvatarOptions(size = 40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recipient.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ReactionRow(reaction: ReactionUiModel, modifier: Modifier = Modifier) {
    val odinId = remember(reaction.odinId) { OdinId(reaction.odinId) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "${reaction.displayName}, ${reaction.emoji}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PublicAvatar(
            odinId = odinId,
            initials = reaction.displayName.firstOrNull()?.toString(),
            options = AvatarOptions(size = 40.dp),
        )
        Text(
            text = reaction.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = reaction.emoji,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
