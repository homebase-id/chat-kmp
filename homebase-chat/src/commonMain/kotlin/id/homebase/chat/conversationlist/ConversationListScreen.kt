package id.homebase.chat.conversationlist

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.archivedconversations.ArchivedConversationsUi
import id.homebase.chat.archivedconversations.ArchivedConversationsUiAction
import id.homebase.chat.archivedconversations.ArchivedConversationsUiState
import id.homebase.chat.archivedconversations.ArchivedConversationsViewModel
import id.homebase.chat.widget.ConversationListPane
import id.homebase.chat.widget.ConversationMessagesPane
import id.homebase.chat.widget.EmptyDetailPane
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.connections.ConnectRequestAction
import id.homebase.core.connections.ConnectRequestBottomSheet
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getUriHandler
import id.homebase.core.util.isDesktop
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogText
import id.homebase.core.widget.DialogTitle
import id.homebase.resources.MR
import id.homebase.resources.action_cannot_be_undone
import id.homebase.resources.cancel
import id.homebase.resources.chat_delete
import id.homebase.resources.chat_delete_conversation_title
import id.homebase.resources.chat_message_delete_dialog_title
import id.homebase.resources.chat_message_delete_for_everyone
import id.homebase.resources.chat_message_delete_for_me
import id.homebase.resources.chat_message_discard_draft
import id.homebase.resources.chat_conversation_deleting_in_progress
import id.homebase.resources.chat_introduce_preflight_body
import id.homebase.resources.chat_introduce_preflight_bullet
import id.homebase.resources.chat_introduce_preflight_reason_not_configured
import id.homebase.resources.chat_introduce_preflight_reason_not_connected
import id.homebase.resources.chat_introduce_preflight_reason_not_permitted
import id.homebase.resources.chat_introduce_preflight_reason_ready
import id.homebase.resources.chat_introduce_preflight_reason_rejected
import id.homebase.resources.chat_introduce_preflight_reason_requires_upgrade
import id.homebase.resources.chat_introduce_preflight_reason_unknown
import id.homebase.resources.chat_introduce_preflight_reason_unreachable
import id.homebase.resources.chat_introduce_preflight_send_anyway
import id.homebase.resources.chat_introduce_preflight_skip_and_send
import id.homebase.resources.chat_introduce_preflight_title
import id.homebase.resources.chat_leave_and_delete
import id.homebase.resources.chat_leave_and_delete_conversation_text
import id.homebase.resources.chat_leave_and_delete_conversation_title
import id.homebase.resources.chat_select_a_conversation
import id.homebase.resources.chat_select_a_conversation_subtitle
import id.homebase.resources.discard
import id.homebase.resources.error_unknown
import id.homebase.resources.file_save_failed
import id.homebase.resources.file_saved_to
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    archivedConversationsViewModel: ArchivedConversationsViewModel,
    extendPermissionViewModel: ExtendPermissionViewModel,
    connectRequestViewModel: ConnectRequestViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettingsScreen: () -> Unit,
    onNavigateToNewConversation: () -> Unit,
    onNavigateToContactInfo: (odinId: String) -> Unit,
    onNavigateToConversationSettings: (conversationId: String) -> Unit,
    onNavigateToGroupSettings: (conversationId: String) -> Unit,
    onNavigateToMessageInfo: (conversationId: Uuid, messageId: Uuid, fileId: Uuid) -> Unit,
    onNavigateToCropper: (requestId: Uuid) -> Unit = {},
    onNavigateToDrawer: (requestId: Uuid) -> Unit = {},
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val conversationsUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messagesUiState by viewModel.messagesUiState.collectAsStateWithLifecycle()
    val archivedUiState by archivedConversationsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fileSystemHandler = getUriHandler()
    // Check for missing permissions and show dialog if needed
    ExtendPermissionDialog(
        viewModel = extendPermissionViewModel,
        onCancel = { viewModel.onAction(ConversationListUiAction.ClearSelection) },
    )
    LaunchedEffect(Unit) {
        extendPermissionViewModel.navigateAwayRequest.collect {
            viewModel.onAction(ConversationListUiAction.ClearSelection)
        }
    }

    val infoString = (conversationsUiState.uiEvent as? ConversationListUiEvent.ShowInfoMessage)?.res?.let { stringResource(it) } ?: ""
    LaunchedEffect(conversationsUiState.uiEvent) {
        when (val event = conversationsUiState.uiEvent) {
            is ConversationListUiEvent.NavigateBack -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is ConversationListUiEvent.ShowErrorMessage -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }

            is ConversationListUiEvent.ShowInfoMessage -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = infoString) }
            }

            is ConversationListUiEvent.NavigateToNewConversation -> {
                viewModel.eventConsumed()
                onNavigateToNewConversation()
            }

            is ConversationListUiEvent.NavigateToContactInfo -> {
                viewModel.eventConsumed()
                onNavigateToContactInfo(event.odinId)
            }

            is ConversationListUiEvent.NavigateToGroupSettings -> {
                viewModel.eventConsumed()
                onNavigateToGroupSettings(event.conversationId)
            }

            is ConversationListUiEvent.NavigateToConversationSettings -> {
                viewModel.eventConsumed()
                onNavigateToConversationSettings(event.conversationId)
            }

            is ConversationListUiEvent.NavigateToMessageInfo -> {
                viewModel.eventConsumed()
                onNavigateToMessageInfo(
                    event.message.conversationId,
                    event.message.id,
                    event.message.fileId,
                )
            }

            is ConversationListUiEvent.ShareText -> {
                viewModel.eventConsumed()
                fileSystemHandler.shareText(event.text)
            }

            is ConversationListUiEvent.ShareFile -> {
                viewModel.eventConsumed()
                fileSystemHandler.shareFile(Path(event.filePath))
            }

            is ConversationListUiEvent.OpenFile -> {
                viewModel.eventConsumed()
                fileSystemHandler.openFile(Path(event.filePath), showChooser = true)
            }

            is ConversationListUiEvent.SaveFileToDevice -> {
                viewModel.eventConsumed()
                fileSystemHandler.saveFile(
                    file = Path(event.filePath),
                    suggestedName = event.suggestedName,
                    onSuccess = { location ->
                        scope.launch {
                            val msg = TranslationUtil.getString(MR.string.file_saved_to, location)
                            snackbarHostState.showSnackbar(msg)
                        }
                    },
                    onError = { error ->
                        scope.launch {
                            val fallback = error.message
                                ?: TranslationUtil.getString(MR.string.error_unknown)
                            val msg = TranslationUtil.getString(
                                MR.string.file_save_failed,
                                fallback,
                            )
                            snackbarHostState.showSnackbar(msg)
                        }
                    },
                )
            }

            is ConversationListUiEvent.OpenUrl -> {
                viewModel.eventConsumed()
                fileSystemHandler.openUrl(event.url)
            }

            is ConversationListUiEvent.OpenSendConnectionRequestDialog -> {
                viewModel.eventConsumed()
                connectRequestViewModel.onAction(
                    ConnectRequestAction.OpenDialogWithRecipient(event.odinId)
                )
            }

            is ConversationListUiEvent.NavigateToCropper -> {
                viewModel.eventConsumed()
                onNavigateToCropper(event.requestId)
            }

            is ConversationListUiEvent.NavigateToDrawer -> {
                viewModel.eventConsumed()
                onNavigateToDrawer(event.requestId)
            }

            null -> {}
        }
    }

    ConnectRequestBottomSheet(
        viewModel = connectRequestViewModel,
        snackbarHostState = snackbarHostState,
    )

    when (val dialog = conversationsUiState.uiDialog) {
        null -> {}
        is ConversationListUiDialog.DeleteMessage -> {
            Dialog(onDismissRequest = { viewModel.dialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.chat_message_delete_for_me),
                            onPrimaryClick = {
                                viewModel.onAction(
                                    ConversationListUiAction.DeleteMessageForMe(
                                        dialog.messageId
                                    )
                                )
                                viewModel.dialogClosed()
                            },
                            secondaryText = if (dialog.allowDeleteForEveryone) stringResource(
                                MR.string.chat_message_delete_for_everyone
                            )
                            else null,
                            onSecondaryClick = {
                                if (dialog.allowDeleteForEveryone) {
                                    viewModel.onAction(
                                        ConversationListUiAction.DeleteMessageForEveryone(
                                            dialog.messageId
                                        )
                                    )
                                    viewModel.dialogClosed()
                                }
                            },
                            tertiaryText = stringResource(MR.string.cancel),
                            onTertiaryClick = { viewModel.dialogClosed() },
                            showButtonsVertically = true
                        )
                    }) {
                    DialogTitle(
                        text = stringResource(MR.string.chat_message_delete_dialog_title),
                    )
                }
            }
        }

        is ConversationListUiDialog.DeleteConversation -> {
            // Detect "group I'm still an active member of" — in that case, surface
            // a combined "Leave & delete" action instead of plain delete (which the
            // service would reject with IllegalStateException). The user no longer
            // has to leave first in a separate step.
            val convo = conversationsUiState.activeConversations
                .find { it.conversation.id == dialog.conversationId }?.conversation
            val needsLeaveFirst = convo != null
                && convo.isGroupConversation
                && convo.conversationState == id.homebase.chat.data.ConversationState.Active

            Dialog(onDismissRequest = { viewModel.dialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(
                                if (needsLeaveFirst) MR.string.chat_leave_and_delete
                                else MR.string.chat_delete
                            ),
                            onPrimaryClick = {
                                if (needsLeaveFirst) {
                                    viewModel.onAction(
                                        ConversationListUiAction.ConfirmLeaveAndDeleteConversation(
                                            dialog.conversationId
                                        )
                                    )
                                } else {
                                    viewModel.onAction(
                                        ConversationListUiAction.ConfirmDeleteConversation(
                                            dialog.conversationId
                                        )
                                    )
                                }
                                viewModel.dialogClosed()
                            },
                            secondaryText = stringResource(MR.string.cancel),
                            onSecondaryClick = { viewModel.dialogClosed() },
                        )
                    }) {
                    DialogTitle(
                        text = stringResource(
                            if (needsLeaveFirst) MR.string.chat_leave_and_delete_conversation_title
                            else MR.string.chat_delete_conversation_title
                        ),
                    )
                    DialogText(
                        text = stringResource(
                            if (needsLeaveFirst) MR.string.chat_leave_and_delete_conversation_text
                            else MR.string.action_cannot_be_undone
                        ),
                    )
                }
            }
        }

        is ConversationListUiDialog.DiscardDraft -> {
            Dialog(onDismissRequest = { viewModel.dialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.discard),
                            onPrimaryClick = {
                                viewModel.onAction(
                                    ConversationListUiAction.EditMessage(
                                        messageId = dialog.messageId,
                                        versionTag = dialog.versionTag,
                                        ignoreDraft = true
                                    )
                                )
                                viewModel.dialogClosed()
                            },
                            secondaryText = stringResource(MR.string.cancel),
                            onSecondaryClick = {
                                viewModel.dialogClosed()
                            },
                        )
                    }) {
                    DialogTitle(
                        text = stringResource(MR.string.chat_message_discard_draft),
                    )
                    DialogText(
                        text = stringResource(MR.string.action_cannot_be_undone),
                    )
                }
            }
        }

        is ConversationListUiDialog.IntroducePreflight -> {
            // Build a per-recipient list with status-specific reasons. Recipients are
            // looked up against the enriched contacts on the active conversation so the
            // displayed name matches the rest of the UI ("Bob" instead of "bob.demo.rocks");
            // we fall back to the raw domain when there's no contact match.
            val convo = conversationsUiState.activeConversations
                .find { it.conversation.id == dialog.conversationId }
            val nameFor: (id.homebase.api.common.OdinId) -> String = { odinId ->
                convo?.participants?.firstOrNull { it.odinId == odinId }?.name
                    ?: odinId.domainName
            }
            IntroducePreflightDialog(
                dialog = dialog,
                nameFor = nameFor,
                onSendAnyway = {
                    viewModel.onAction(
                        ConversationListUiAction.IntroduceSendAnyway(
                            conversationId = dialog.conversationId,
                            message = dialog.message,
                        )
                    )
                },
                onSendReadyOnly = {
                    viewModel.onAction(
                        ConversationListUiAction.IntroduceSendReadyOnly(
                            conversationId = dialog.conversationId,
                            readyRecipients = dialog.result.readyRecipients,
                            message = dialog.message,
                        )
                    )
                },
                onCancel = {
                    viewModel.onAction(ConversationListUiAction.IntroduceCancel)
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ConversationListUi(
            snackbarHostState = snackbarHostState,
            uiState = conversationsUiState,
            messagesUiState = messagesUiState,
            archivedConversationsUiState = archivedUiState,
            conversationSearchTextFieldState = viewModel.conversationSearchTextState,
            messageInputTextFieldState = viewModel.messageInputTextState,
            messagesSearchTextState = viewModel.messagesSearchTextState,
            onUiAction = viewModel::onAction,
            onEventConsumed = viewModel::eventConsumed,
            onNavigateToSettingsScreen = onNavigateToSettingsScreen,
            onDetailPaneVisibilityChanged = onDetailPaneVisibilityChanged
        )

        conversationsUiState.inFlightOperationLabel?.let { label ->
            InFlightOperationOverlay(label)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InFlightOperationOverlay(label: org.jetbrains.compose.resources.StringResource) {
    // Mirrors LeavingGroupOverlay in GroupSettingsScreen — full-screen scrim
    // + spinner so the user gets visible feedback that the delete is running,
    // and double-taps are absorbed.
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { /* no-op while deleting */ }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) { awaitPointerEvent() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Confirmation dialog shown when the introduction preflight reports any non-Ready
 * recipients. Lists each affected recipient with a human-readable reason and gives
 * the user three choices: send anyway with the original list, skip the non-Ready
 * recipients and send to the rest, or cancel.
 *
 * "Send anyway" still passes the FULL original recipient list to the server —
 * preflight is advisory; the server's outbox retries the actual failures in the
 * background.
 */
@Composable
private fun IntroducePreflightDialog(
    dialog: ConversationListUiDialog.IntroducePreflight,
    nameFor: (id.homebase.api.common.OdinId) -> String,
    onSendAnyway: () -> Unit,
    onSendReadyOnly: () -> Unit,
    onCancel: () -> Unit,
) {
    val nonReady = dialog.result.nonReady
    val readyCount = dialog.result.readyRecipients.size
    Dialog(onDismissRequest = onCancel) {
        DialogCard(
            buttons = {
                DialogButtons(
                    primaryText = stringResource(MR.string.chat_introduce_preflight_send_anyway),
                    onPrimaryClick = onSendAnyway,
                    // Only offer "Skip & send to ready" when there's actually a Ready
                    // subset — if nobody passed preflight, this option is a no-op.
                    secondaryText = if (readyCount > 0) {
                        stringResource(MR.string.chat_introduce_preflight_skip_and_send)
                    } else null,
                    onSecondaryClick = if (readyCount > 0) {
                        onSendReadyOnly
                    } else null,
                    tertiaryText = stringResource(MR.string.cancel),
                    onTertiaryClick = onCancel,
                    showButtonsVertically = true,
                )
            }
        ) {
            DialogTitle(
                text = stringResource(MR.string.chat_introduce_preflight_title),
            )
            DialogText(
                text = stringResource(MR.string.chat_introduce_preflight_body),
            )
            // Per-recipient reasons. Compact list — one row per non-Ready recipient.
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                nonReady.forEach { entry ->
                    val name = nameFor(entry.recipient)
                    val reason = stringResource(entry.status.reasonResource(), name)
                    val text = if (entry.status ==
                        id.homebase.api.client.connections.IntroductionPreflightStatus.UnknownError
                        && !entry.detail.isNullOrBlank()
                    ) {
                        // Splice in server detail as a fallback when the per-status
                        // string is too generic. Format: "<base> <detail>".
                        "$reason ${entry.detail}"
                    } else {
                        reason
                    }
                    // Build the bullet-prefixed line via stringResource so the
                    // bullet character itself is localized (and so the line we
                    // pass to Text below is an identifier, not a literal — the
                    // architecture konsist test forbids in-Composable string
                    // literals at the Text call site).
                    val bulletLine = stringResource(
                        MR.string.chat_introduce_preflight_bullet,
                        text,
                    )
                    Text(
                        text = bulletLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Maps a preflight status to its localized per-recipient reason string. The
 *  caller passes the recipient's display name as the format arg `%1$s`. */
private fun id.homebase.api.client.connections.IntroductionPreflightStatus.reasonResource():
    org.jetbrains.compose.resources.StringResource = when (this) {
    id.homebase.api.client.connections.IntroductionPreflightStatus.Ready ->
        // Should never appear in the dialog, but provide a sensible default anyway.
        MR.string.chat_introduce_preflight_reason_ready
    id.homebase.api.client.connections.IntroductionPreflightStatus.NotConnected ->
        MR.string.chat_introduce_preflight_reason_not_connected
    id.homebase.api.client.connections.IntroductionPreflightStatus.RecipientNotConfigured ->
        MR.string.chat_introduce_preflight_reason_not_configured
    id.homebase.api.client.connections.IntroductionPreflightStatus.RecipientRequiresUpgrade ->
        MR.string.chat_introduce_preflight_reason_requires_upgrade
    id.homebase.api.client.connections.IntroductionPreflightStatus.IntroductionsNotPermitted ->
        MR.string.chat_introduce_preflight_reason_not_permitted
    id.homebase.api.client.connections.IntroductionPreflightStatus.RecipientRejected ->
        MR.string.chat_introduce_preflight_reason_rejected
    id.homebase.api.client.connections.IntroductionPreflightStatus.Unreachable ->
        MR.string.chat_introduce_preflight_reason_unreachable
    id.homebase.api.client.connections.IntroductionPreflightStatus.UnknownError ->
        MR.string.chat_introduce_preflight_reason_unknown
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ConversationListUi(
    snackbarHostState: SnackbarHostState,
    uiState: ConversationListUiState,
    messagesUiState: MessageListUiState,
    archivedConversationsUiState: ArchivedConversationsUiState,
    conversationSearchTextFieldState: TextFieldState,
    messageInputTextFieldState: RichTextState,
    messagesSearchTextState: TextFieldState,
    onUiAction: (ConversationListUiAction) -> Unit,
    /** Forwarded from the outer screen so this composable can mark VM events as
     *  consumed when it handles them locally (e.g. CloseDetailPane, which needs
     *  the in-scope scaffoldNavigator). Defaults to no-op so the existing call
     *  site at the bottom of the file (ConversationListUiPreview) and any other
     *  caller that doesn't drive events still compiles. */
    onEventConsumed: () -> Unit = {},
    onNavigateToSettingsScreen: () -> Unit,
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val defaultDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo)
    val isExpanded = isDesktop() &&  windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(800)
    val scaffoldDirective = PaneScaffoldDirective(
        maxHorizontalPartitions = if (isExpanded) 2 else 1,
        horizontalPartitionSpacerSize = 0.dp, // Remove the white border
        maxVerticalPartitions = defaultDirective.maxVerticalPartitions,
        verticalPartitionSpacerSize = defaultDirective.verticalPartitionSpacerSize,
        defaultPanePreferredWidth = 360.dp, // Slightly wider default for chat list
        excludedBounds = defaultDirective.excludedBounds
    )
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<Uuid>(
        scaffoldDirective = scaffoldDirective,
        initialDestinationHistory = if (scaffoldDirective.maxHorizontalPartitions > 1) {
            listOf(
                ThreePaneScaffoldDestinationItem(
                    ListDetailPaneScaffoldRole.List
                ), ThreePaneScaffoldDestinationItem(
                    ListDetailPaneScaffoldRole.Detail
                )
            )
        } else {
            listOf(
                ThreePaneScaffoldDestinationItem(
                    ListDetailPaneScaffoldRole.List
                )
            )
        }
    )
    val scope = rememberCoroutineScope()
    val backNavigationBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange

    // DIAGNOSTIC (diag-coldstart-main-stall-watchdog only): log every scaffold
    // destination change, so a navigation that no-ops or gets popped right back
    // (Detail→List) during cold start is visible — that reads as a dropped tap from
    // the user's side. Pair with ConvNav (navigateTo bracketing) and ConvRowTap.
    LaunchedEffect(scaffoldNavigator) {
        snapshotFlow { scaffoldNavigator.currentDestination }
            .collect { dest ->
                Logger.i(tag = "ConvNav") {
                    "scaffold destination → pane=${dest?.pane} key=${dest?.contentKey}"
                }
            }
    }

    // closeDetailPaneRequest handler — has to live inside ConversationListUi (not
    // the outer screen) because scaffoldNavigator + backNavigationBehavior are in
    // scope here. Uses a dedicated state field rather than uiEvent because the
    // delete flow also fires a snackbar event back-to-back, and uiEvent is a
    // single slot — the snackbar would overwrite the close request.
    //
    // Pops the detail pane with PopUntilContentChange (same mechanic the
    // BackHandler uses) so this works in BOTH expanded (desktop) and compact
    // (resized-narrow / phone) layouts. PopUntilScaffoldValueChange would no-op
    // on expanded because the visible panes don't change there.
    LaunchedEffect(uiState.closeDetailPaneRequest) {
        if (uiState.closeDetailPaneRequest != null) {
            if (scaffoldNavigator.canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) {
                scaffoldNavigator.navigateBack(BackNavigationBehavior.PopUntilContentChange)
            }
            onUiAction(ConversationListUiAction.CloseDetailPaneRequestConsumed)
        }
    }

    // Detect if detail pane is visible and list pane is hidden (compact view showing only detail)
    val isListPaneHidden =
        scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    val isDetailPaneVisible =
        scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden
    val showingOnlyDetail = isListPaneHidden && isDetailPaneVisible

    LaunchedEffect(isExpanded) {
        if (!isExpanded && scaffoldNavigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail) {
            // Optional: If you want to force it back to list view when shrinking
            scaffoldNavigator.navigateBack()
        }
    }

    // Cold-start guard against the pane scaffold's rememberSaveable-restored Detail
    // destination outliving its conversation. See ColdStartDetailGuard's KDoc.
    val loadedConversationIds = remember(uiState.activeConversations) {
        uiState.activeConversations.mapTo(hashSetOf()) { it.conversation.id }
    }
    ColdStartDetailGuard(
        scaffoldNavigator = scaffoldNavigator,
        loadedConversationIds = loadedConversationIds,
        maxHorizontalPartitions = scaffoldDirective.maxHorizontalPartitions,
    )

    val partitions = scaffoldDirective.maxHorizontalPartitions
    LaunchedEffect(partitions) {
        if (partitions > 1) {
            // This ensures the Detail role is added to the active visible roles
            Logger.i(tag = "ConversationListUi") { "Showing details more than 1 partition for ${uiState.selectedConversationId}" }
            scaffoldNavigator.navigateTo(
                ListDetailPaneScaffoldRole.Detail,
                uiState.selectedConversationId,
            )
        }
    }

    // Notify parent about detail pane visibility in compact view
    LaunchedEffect(showingOnlyDetail) { onDetailPaneVisibilityChanged(showingOnlyDetail) }

    // Installs the coupled cleanup + swap effects that drive notification-tap navigation.
    // See NotificationNavigationEffects.kt for why the two effects must be coordinated.
    NotificationNavigationEffects(
        scaffoldNavigator = scaffoldNavigator,
        selectedConversationId = uiState.selectedConversationId,
        scaffoldDirective = scaffoldDirective,
        onClearSelection = { onUiAction(ConversationListUiAction.ClearSelection) },
    )

    @Suppress("DEPRECATION") BackHandler(scaffoldNavigator.canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) {
        scope.launch {
            if (messagesUiState.fullScreenOverlay != null) {
                onUiAction(ConversationListUiAction.CloseFullScreenOverlay)
            } else if (!isDesktop()) {
                scaffoldNavigator.navigateBack(BackNavigationBehavior.PopUntilContentChange)
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) {
        ListDetailPaneScaffold(
            modifier = Modifier.fillMaxSize(),
            directive = scaffoldNavigator.scaffoldDirective,
            scaffoldState = scaffoldNavigator.scaffoldState,
            listPane = {
                AnimatedPane(modifier = Modifier) {
                    if (uiState.showArchived) {
                        ArchivedConversationsUi(
                            snackbarHostState = snackbarHostState,
                            uiState = archivedConversationsUiState,
                            selectedConversationId = uiState.selectedConversationId,
                            onUiAction = { action ->
                                when (action) {
                                    is ArchivedConversationsUiAction.BackClicked -> {
                                        onUiAction(ConversationListUiAction.ArchiveBackClicked)
                                    }
                                    is ArchivedConversationsUiAction.ShowConversation -> {
                                        onUiAction(ConversationListUiAction.ConversationClicked(action.conversationId, null))
                                        Logger.i(tag = "ConversationListUi") { "Navigating to detail for ${action.conversationId} from archived" }
                                        scope.launch {
                                            scaffoldNavigator.navigateTo(
                                                ListDetailPaneScaffoldRole.Detail,
                                                action.conversationId
                                            )
                                        }
                                    }
                                    is ArchivedConversationsUiAction.ShowConversationSettings -> {
                                        onUiAction(ConversationListUiAction.ShowConversationSettings(action.conversation))
                                    }
                                    is ArchivedConversationsUiAction.UnarchiveConversation -> {
                                        onUiAction(ConversationListUiAction.UnarchiveConversation(action.conversationId))
                                    }
                                }
                            },
                        )
                    } else {
                        ConversationListPane(
                            uiState = uiState,
                            selectedConversationId = scaffoldNavigator.currentDestination?.contentKey,
                            searchTextState = conversationSearchTextFieldState,
                            onProfileClick = onNavigateToSettingsScreen,
                            onUiAction = onUiAction,
                            onConversationSelected = {
                                Logger.i(tag = "ConversationListUi") { "Navigating to detail for $it" }
                                scope.launch {
                                    // DIAGNOSTIC (diag branch only): bracket navigateTo so we can
                                    // tell "onClick fired but the scaffold didn't move" apart from
                                    // a dropped tap. ConvNav scaffold-destination logger above shows
                                    // the resulting transition (or absence of one).
                                    Logger.i(tag = "ConvNav") {
                                        "navigateTo start target=$it before=${scaffoldNavigator.currentDestination?.contentKey}"
                                    }
                                    scaffoldNavigator.navigateTo(
                                        ListDetailPaneScaffoldRole.Detail,
                                        it
                                    )
                                    Logger.i(tag = "ConvNav") {
                                        "navigateTo done target=$it now=${scaffoldNavigator.currentDestination?.contentKey} pane=${scaffoldNavigator.currentDestination?.pane}"
                                    }
                                }
                            }
                        )
                    }
                }
            },
            detailPane = {
                AnimatedPane {
                    val contentKey = scaffoldNavigator.currentDestination?.contentKey
                    val conversation =
                        uiState.activeConversations.find { it.conversation.id == contentKey }
                    LaunchedEffect(contentKey, conversation != null) {
                        Logger.i(tag = "ConversationListUi") {
                            "detailPane render: contentKey=$contentKey, conversationFound=${conversation != null}, activeConversationsSize=${uiState.activeConversations.size}"
                        }
                    }
                    if (conversation != null) {
                        key(conversation.conversation.id) {
                            ConversationMessagesPane(
                                conversation = conversation,
                                uiState = messagesUiState,
                                textFieldState = messageInputTextFieldState,
                                searchTextState = messagesSearchTextState,
                                showBackButton = scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden,
                                onBackClick = {
                                    onUiAction(ConversationListUiAction.ClearSelection)
                                    scope.launch {
                                        scaffoldNavigator.navigateBack(
                                            backNavigationBehavior
                                        )
                                    }
                                },
                                onUiAction = onUiAction,
                            )
                        }
                    } else {
                        EmptyDetailPane(
                            title = stringResource(
                                MR.string.chat_select_a_conversation
                            ), subtitle = stringResource(
                                MR.string.chat_select_a_conversation_subtitle
                            )
                        )
                    }
                }
            },
            paneExpansionState = rememberPaneExpansionState(
                keyProvider = scaffoldNavigator.scaffoldValue,
                anchors = listOf(
                    PaneExpansionAnchor.Offset.fromStart(96.dp),
                    PaneExpansionAnchor.Offset.fromStart(280.dp),
                    PaneExpansionAnchor.Offset.fromStart(320.dp),
                    PaneExpansionAnchor.Offset.fromStart(360.dp),
                    PaneExpansionAnchor.Offset.fromStart(400.dp),
                    PaneExpansionAnchor.Offset.fromStart(440.dp),
                    PaneExpansionAnchor.Offset.fromStart(480.dp),
                ),
            ),
            // IMPORTANT: paneExpansionDragHandle with VerticalDragHandle is intentionally
            // omitted here. On Compose Desktop (Skiko), the VerticalDragHandle's pointer/hover
            // tracking causes the rendering loop to run at ~164 fps continuously — even when the
            // app is completely idle — pinning the GPU at ~15%. This is a known limitation of
            // Compose Multiplatform's pointer input handling on desktop. The drag handle can be
            // re-enabled once the upstream issue is resolved.
            // See: paneExpansionDragHandle = { state -> VerticalDragHandle(...) }
            )
    }
}

@Preview
@Composable
fun ConversationListUiPreview() {
    HomebaseTheme {
        ConversationListUi(
            snackbarHostState = SnackbarHostState(),
            uiState = ConversationListUiState(),
            messagesUiState = MessageListUiState(),
            archivedConversationsUiState = ArchivedConversationsUiState(),
            conversationSearchTextFieldState = TextFieldState(),
            messagesSearchTextState = TextFieldState(),
            messageInputTextFieldState = RichTextState(),
            onUiAction = {},
            onNavigateToSettingsScreen = {},
        )
    }
}
