package id.homebase.chat.poll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_poll_add_option
import id.homebase.resources.chat_poll_allow_multiple
import id.homebase.resources.chat_poll_composer_title
import id.homebase.resources.chat_poll_option_hint
import id.homebase.resources.chat_poll_options_label
import id.homebase.resources.chat_poll_question_hint
import id.homebase.resources.chat_poll_question_label
import id.homebase.resources.chat_poll_remove_option
import id.homebase.resources.chat_poll_share
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Fullscreen composer for a Poll message. Mirrors the DiceRollComposerSheet
 * shape: Dialog → Scaffold → form → primary action. State is local — the
 * composer is short-lived (open → fill → send/dismiss).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollComposerSheet(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        PollComposerContent(
            conversationId = conversationId,
            onDismiss = onDismiss,
            onSent = onSent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun PollComposerContent(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sender: ChatMessageSenderService = koinInject()
    val scope = rememberCoroutineScope()
    val brand = HomebaseTheme.extendedColors.bubbleSentSurface

    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var allowMultiple by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    val isValid by remember {
        derivedStateOf {
            question.isNotBlank() &&
                options.count { it.isNotBlank() } >= PollDescriptor.MIN_OPTIONS &&
                !sending
        }
    }

    val doSend: () -> Unit = {
        if (isValid) {
            sending = true
            scope.launch {
                val descriptor = PollDescriptor(
                    question = question.trim().truncateToCodePoints(PollDescriptor.MAX_QUESTION_CP),
                    options = options.map { it.trim() }.filter { it.isNotBlank() }
                        .map { it.truncateToCodePoints(PollDescriptor.MAX_OPTION_CP) },
                    allowMultiple = allowMultiple,
                )
                runCatching {
                    sender.sendNewTypedMessage(
                        messageUniqueId = Uuid.random(),
                        conversationId = conversationId,
                        content = MessageContent.Poll(descriptor),
                        previousMessageUniqueId = null,
                    )
                }
                onSent()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_poll_composer_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(MR.string.cancel),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = doSend,
                        enabled = isValid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = brand,
                            contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                        ),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(MR.string.chat_poll_share))
                    }
                },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Question field
            Text(
                text = stringResource(MR.string.chat_poll_question_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = question,
                onValueChange = { question = it.truncateToCodePoints(PollDescriptor.MAX_QUESTION_CP) },
                placeholder = { Text(stringResource(MR.string.chat_poll_question_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
            )

            Spacer(Modifier.height(4.dp))

            // Options section
            Text(
                text = stringResource(MR.string.chat_poll_options_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            options.forEachIndexed { index, option ->
                // Build hint outside the composable to satisfy Konsist string-literal rule.
                val optionHint = stringResource(MR.string.chat_poll_option_hint, index + 1)
                val removeLabel = stringResource(MR.string.chat_poll_remove_option)
                OutlinedTextField(
                    value = option,
                    onValueChange = { options[index] = it.truncateToCodePoints(PollDescriptor.MAX_OPTION_CP) },
                    placeholder = { Text(optionHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = if (options.size > PollDescriptor.MIN_OPTIONS) {
                        {
                            IconButton(
                                onClick = { options.removeAt(index) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = removeLabel,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else null,
                )
            }

            // Add option button
            TextButton(
                onClick = { options.add("") },
                enabled = options.size < PollDescriptor.MAX_OPTIONS,
            ) {
                Icon(
                    imageVector = Icons.Default.HowToVote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(MR.string.chat_poll_add_option))
            }

            // Allow multiple votes toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.chat_poll_allow_multiple),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = allowMultiple,
                    onCheckedChange = { allowMultiple = it },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
