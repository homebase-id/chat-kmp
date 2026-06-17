package id.homebase.chat.poll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.composer.ComposerEditableField
import id.homebase.chat.composer.ComposerTitleField
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_poll_add_option
import id.homebase.resources.chat_poll_allow_multiple
import id.homebase.resources.chat_poll_option_hint
import id.homebase.resources.chat_poll_question_hint
import id.homebase.resources.chat_poll_remove_option
import id.homebase.resources.chat_poll_reorder_option
import id.homebase.resources.chat_poll_share
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableColumn

/**
 * Bottom-sheet composer for a Poll message, modeled on the same Material 3
 * quick-create language as `event/EventComposerSheet` and
 * `groodle/GroodleComposerSheet`: a borderless title, then flat icon-led rows.
 * Options are a drag-to-reorder list (Calvin-LL/Reorderable).
 *
 * Presentation only — [PollDescriptor], the wire format and the send path are
 * unchanged. State is local `remember` (the composer is short-lived:
 * open → fill → send/dismiss).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollComposerSheet(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        PollComposerContent(
            conversationId = conversationId,
            sheetState = sheetState,
            onDismiss = onDismiss,
            onSent = onSent,
        )
    }
}

/** One editable poll option. [id] is a stable local key for reorder/list rendering. */
private data class OptionDraft(val id: Long, val text: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun PollComposerContent(
    conversationId: Uuid,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sender: ChatMessageSenderService = koinInject()
    val scope = rememberCoroutineScope()
    val brand = HomebaseTheme.extendedColors.bubbleSentSurface

    var question by remember { mutableStateOf("") }
    // ReorderableColumn keys its fixed-size internal offset arrays on the LIST
    // INSTANCE (remember(list, spacing)), so the list must be an immutable List
    // reassigned wholesale on every change — a mutated-in-place SnapshotStateList
    // keeps the same instance, the arrays never resize, and a newly added row
    // indexes past their end (IndexOutOfBoundsException during placement).
    var options by remember { mutableStateOf(listOf(OptionDraft(0, ""), OptionDraft(1, ""))) }
    var nextId by remember { mutableStateOf(2L) }
    var allowMultiple by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    val isValid by remember {
        derivedStateOf {
            question.isNotBlank() &&
                options.count { it.text.isNotBlank() } >= PollDescriptor.MIN_OPTIONS &&
                !sending
        }
    }

    val dismiss: () -> Unit = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    val doSend: () -> Unit = {
        if (isValid) {
            sending = true
            scope.launch {
                val descriptor = PollDescriptor(
                    question = question.trim().truncateToCodePoints(PollDescriptor.MAX_QUESTION_CP),
                    options = options.map { it.text.trim() }.filter { it.isNotBlank() }
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
                sheetState.hide()
                onSent()
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
        // Top bar: close (start) · Send (end).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = dismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.cancel),
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = doSend,
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = brand,
                    contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                ),
            ) {
                Text(stringResource(MR.string.chat_poll_share))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // imePadding INSIDE the scroll pads the content (not the viewport) so
                // the focused field scrolls above the keyboard. The ModalBottomSheet
                // already lifts for the IME, so shrinking the viewport here would
                // double-count and collapse the sheet on Android. Matches
                // EventComposerSheet / GroodleComposerSheet.
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            // Question — borderless headline with a brand-blue underline indicator.
            ComposerTitleField(
                value = question,
                onValueChange = { question = it.truncateToCodePoints(PollDescriptor.MAX_QUESTION_CP) },
                placeholder = stringResource(MR.string.chat_poll_question_hint),
                brand = brand,
            )

            Spacer(Modifier.height(16.dp))

            // Options — a flush-left, drag-to-reorder list aligned with the question
            // headline (no leading icon gutter; each row carries its own drag handle).
            // Below MIN_OPTIONS the list is locked at its two seed rows, so the remove
            // control only appears once a third option exists.
            val canRemove = options.size > PollDescriptor.MIN_OPTIONS
            ReorderableColumn(
                list = options,
                onSettle = { from, to ->
                    options = options.toMutableList().apply { add(to, removeAt(from)) }
                },
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { index, item, _ ->
                key(item.id) {
                    ReorderableItem {
                        // Build the per-row strings outside the field/icon to satisfy
                        // the Konsist no-literal-Text rule.
                        val optionHint = stringResource(MR.string.chat_poll_option_hint, index + 1)
                        val removeLabel = stringResource(MR.string.chat_poll_remove_option)
                        val reorderLabel = stringResource(MR.string.chat_poll_reorder_option)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComposerEditableField(
                                value = item.text,
                                onValueChange = { new ->
                                    val capped = new.truncateToCodePoints(PollDescriptor.MAX_OPTION_CP)
                                    options = options.map {
                                        if (it.id == item.id) it.copy(text = capped) else it
                                    }
                                },
                                placeholder = optionHint,
                                singleLine = true,
                                cursorColor = brand,
                            )
                            if (canRemove) {
                                // Default IconButton sizing keeps the 48dp touch target.
                                IconButton(onClick = { options = options.filterNot { it.id == item.id } }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = removeLabel,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // Drag handle — draggableHandle() comes from the
                            // ReorderableItem scope; the onClick is a no-op.
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DragHandle,
                                    contentDescription = reorderLabel,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Plain-text action; the negative offset cancels the button's content
            // padding so its + icon left-aligns with the option text above it.
            TextButton(
                onClick = {
                    if (options.size < PollDescriptor.MAX_OPTIONS) {
                        options = options + OptionDraft(nextId, "")
                        nextId += 1
                    }
                },
                enabled = options.size < PollDescriptor.MAX_OPTIONS,
                modifier = Modifier.offset(x = (-12).dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(MR.string.chat_poll_add_option))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Allow multiple answers — flush-left label + trailing switch.
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
                Switch(checked = allowMultiple, onCheckedChange = { allowMultiple = it })
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
