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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.common.util.formatBytes
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.widget.ReceivedMessageBubbleDisplayOnly
import id.homebase.chat.widget.SentMessageBubbleDisplayOnly
import id.homebase.chat.widget.deliveryFailureTint
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.util.formateDateTime
import id.homebase.resources.MR
import id.homebase.resources.chat_message_info
import id.homebase.resources.delivered_to
import id.homebase.resources.details
import id.homebase.resources.failed
import id.homebase.resources.action_retry
import id.homebase.resources.label_edited
import id.homebase.resources.label_received
import id.homebase.resources.copy_message_id
import id.homebase.resources.label_message_id
import id.homebase.resources.label_sent
import id.homebase.resources.error_delivery_failed
import id.homebase.resources.action_try_now
import id.homebase.resources.msg_next_attempt_countdown
import id.homebase.resources.msg_next_attempt_shortly
import id.homebase.resources.msg_send_attempt
import id.homebase.resources.msg_stuck_reason
import id.homebase.resources.msg_waiting_on_earlier_message
import id.homebase.resources.msg_status_delivery_details_unavailable
import id.homebase.resources.msg_status_failed_to_send
import id.homebase.resources.msg_status_sending
import id.homebase.resources.msg_status_sent
import id.homebase.resources.msg_still_in_outbox
import id.homebase.resources.label_size
import id.homebase.resources.menu_back
import id.homebase.resources.reactions
import id.homebase.resources.read_by
import id.homebase.resources.sending_to
import id.homebase.resources.uploaded
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Live "next attempt" label for a queued message: ticks once a second toward
 * [deadlineMs] and renders the remaining time as "in m:ss". Returns the
 * "shortly" copy when there's no meaningful deadline ([deadlineMs] null — the
 * row is in flight or fresh) or the countdown has elapsed.
 */
@Composable
private fun rememberNextAttemptText(deadlineMs: Long?): String {
    if (deadlineMs == null) return stringResource(MR.string.msg_next_attempt_shortly)
    var nowMs by remember(deadlineMs) { mutableStateOf(UnixTimeUtc.now().milliseconds) }
    LaunchedEffect(deadlineMs) {
        while (nowMs < deadlineMs) {
            delay(1_000)
            nowMs = UnixTimeUtc.now().milliseconds
        }
    }
    val remainingMs = (deadlineMs - nowMs).coerceAtLeast(0L)
    return if (remainingMs <= 0L) {
        stringResource(MR.string.msg_next_attempt_shortly)
    } else {
        stringResource(MR.string.msg_next_attempt_countdown, formatCountdown(remainingMs))
    }
}

/** Format a remaining-millis span as a compact "m:ss" countdown (e.g. "1:48",
 *  "0:09"). Digits only — safe to pass as a [stringResource] argument. */
private fun formatCountdown(remainingMs: Long): String {
    val totalSeconds = remainingMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val paddedSeconds = if (seconds < 10L) "0$seconds" else "$seconds"
    return "$minutes:$paddedSeconds"
}

@Composable
fun MessageInfoScreen(
    viewModel: MessageInfoViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (uiState.uiEvent) {
        is MessageInfoUiEvent.Back -> {
            viewModel.eventConsumed()
            onNavigateBack()
        }

        // Consumed inside MessageInfoUi, where the snackbar host lives.
        is MessageInfoUiEvent.RetryFailed -> {}

        null -> {}
    }

    MessageInfoUi(
        uiState = uiState,
        onUiAction = viewModel::onUiAction,
        onEventConsumed = viewModel::eventConsumed,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoUi(
    uiState: MessageInfoUiState,
    onUiAction: (MessageInfoUiAction) -> Unit,
    onEventConsumed: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    val retryFailed = uiState.uiEvent as? MessageInfoUiEvent.RetryFailed
    val retryFailedText = retryFailed?.let { stringResource(it.messageRes) }
    LaunchedEffect(retryFailed) {
        if (retryFailed != null && retryFailedText != null) {
            // Consume before showing — showSnackbar suspends until dismissal.
            onEventConsumed()
            snackbarHostState.showSnackbar(retryFailedText)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_message_info)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(MessageInfoUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                if (!uiState.isOwnMessage) {
                    // Inbound: the sender's send-time and our receive-time — both
                    // meaningful for a message that arrived from someone else.
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
                } else {
                    // Outgoing: a single status row reflecting the real send
                    // outcome — never the misleading "App sent / Received" pair,
                    // which is stamped from local timestamps regardless of whether
                    // anything actually left the device.
                    when (uiState.sendState) {
                        OutgoingSendState.Sending -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(MR.string.msg_status_sending),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        OutgoingSendState.Queued -> {
                            // Still in the outbox: it WILL be sent (after its
                            // backoff). Honest copy — attempt count, a live
                            // countdown to the next try (or "waiting on an
                            // earlier message"), and the last failure reason —
                            // plus a Try-now escape hatch, instead of an
                            // indefinite "Sending…" spinner.
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    val statusText = if (uiState.waitingOnEarlierMessage) {
                                        stringResource(MR.string.msg_waiting_on_earlier_message)
                                    } else {
                                        stringResource(
                                            MR.string.msg_still_in_outbox,
                                            rememberNextAttemptText(uiState.nextAttemptAtMs),
                                        )
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                uiState.attemptNumber?.let { attempt ->
                                    Text(
                                        text = stringResource(
                                            MR.string.msg_send_attempt,
                                            attempt,
                                            uiState.maxAttempts,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                uiState.stuckReason?.let { reason ->
                                    Text(
                                        text = stringResource(MR.string.msg_stuck_reason, reason),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                            if (uiState.canTryNow) {
                                OutlinedButton(
                                    onClick = { onUiAction(MessageInfoUiAction.TryNowClicked) },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                ) {
                                    Text(stringResource(MR.string.action_try_now))
                                }
                            }
                        }

                        OutgoingSendState.Sent -> {
                            Text(
                                text = stringResource(
                                    MR.string.msg_status_sent,
                                    uiState.message?.userDate?.let { formateDateTime(it) } ?: "",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }

                        OutgoingSendState.Failed -> {
                            Text(
                                text = stringResource(MR.string.msg_status_failed_to_send),
                                style = MaterialTheme.typography.bodyMedium,
                                color = deliveryFailureTint(),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }

                        OutgoingSendState.DeliveryFailed -> {
                            Text(
                                text = stringResource(MR.string.error_delivery_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = deliveryFailureTint(),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }

                        null -> {}
                    }

                    // A failed message must never show a bare failure headline
                    // with silently-missing reasons: when the per-recipient
                    // transfer history couldn't load, say so.
                    val sendFailed = uiState.sendState == OutgoingSendState.Failed ||
                        uiState.sendState == OutgoingSendState.DeliveryFailed
                    if (sendFailed && uiState.transferHistoryFailed) {
                        Text(
                            text = stringResource(MR.string.msg_status_delivery_details_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }

                    if (uiState.canRetry) {
                        OutlinedButton(
                            onClick = { onUiAction(MessageInfoUiAction.RetryClicked) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(MR.string.action_retry))
                        }
                    }
                }
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

                // One Size row per payload that has a known byte count. Useful
                // for confirming transcode output sizes (e.g. CBR taking
                // effect after the surface-bridge rewrite) and for users
                // gauging how much data a message carries when there's no
                // download button to peek at file properties.
                //
                // Per-payload (not summed) because messages can carry multiple
                // payloads — video + HLS playlist + audio + image — and the
                // per-payload breakdown is more diagnostic than a total.
                val sizedPayloads = remember(uiState.message?.payloads) {
                    uiState.message?.payloads
                        ?.filter { (it.bytesWritten ?: 0L) > 0L }
                        .orEmpty()
                }
                sizedPayloads.forEach { p ->
                    // Label prefers the contentType (e.g. "video/mp2t") which
                    // is more user-meaningful than the payload key. Falls back
                    // to the key for typed payloads with no MIME.
                    val label = p.contentType ?: p.key
                    Text(
                        text = stringResource(
                            MR.string.label_size,
                            "${formatBytes(p.bytesWritten ?: 0L)} ($label)",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                // Message uniqueId — diagnostic, deliberately discreet (small
                // and muted) since it's meaningless to the average user. The
                // tiny copy button puts the full UUID on the clipboard for bug
                // reports / cross-referencing server logs.
                uiState.message?.id?.let { messageId ->
                    val clipboard = LocalClipboard.current
                    val scope = rememberCoroutineScope()
                    val idText = messageId.toString()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = stringResource(MR.string.label_message_id, idText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(clipEntryOf(idText))
                                }
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(MR.string.copy_message_id),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                    color = deliveryFailureTint(),
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
