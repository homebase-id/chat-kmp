package id.homebase.chat.conversationlist

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.widget.ConversationListPane
import id.homebase.chat.widget.ConversationMessagesPane
import id.homebase.chat.widget.EmptyDetailPane
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_message_delete_dialog_title
import id.homebase.resources.chat_message_delete_for_everyone
import id.homebase.resources.chat_message_delete_for_me
import id.homebase.resources.chat_select_a_conversation
import id.homebase.resources.chat_select_a_conversation_subtitle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettingsScreen: () -> Unit,
    onNavigateToNewConversation: () -> Unit,
    onNavigateToContactInfo: (odinId: String) -> Unit,
    onNavigateToMessageInfo: (conversationId: Uuid, messageId: Uuid, fileId: Uuid) -> Unit,
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            is ConversationListUiEvent.NavigateBack -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is ConversationListUiEvent.ShowErrorMessage -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }

            is ConversationListUiEvent.NavigateToNewConversation -> {
                viewModel.eventConsumed()
                onNavigateToNewConversation()
            }

            is ConversationListUiEvent.NavigateToContactInfo -> {
                viewModel.eventConsumed()
                onNavigateToContactInfo(event.odinId)
            }

            is ConversationListUiEvent.NavigateToMessageInfo -> {
                viewModel.eventConsumed()
                onNavigateToMessageInfo(
                    event.message.conversationId,
                    event.message.id,
                    event.message.fileId,
                )
            }

            null -> {}
        }
    }

    when (val dialog = uiState.uiDialog) {
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
                            onTertiaryClick = { viewModel.dialogClosed() })
                    }) {
                    Text(
                        text = stringResource(MR.string.chat_message_delete_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }

    ChatListUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        conversationSearchTextFieldState = viewModel.conversationSearchTextState,
        messageInputTextFieldState = viewModel.messageInputTextState,
        onUiAction = viewModel::onAction,
        onNavigateToSettingsScreen = onNavigateToSettingsScreen,
        onDetailPaneVisibilityChanged = onDetailPaneVisibilityChanged
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatListUi(
    snackbarHostState: SnackbarHostState,
    uiState: ConversationListUiState,
    conversationSearchTextFieldState: TextFieldState,
    messageInputTextFieldState: RichTextState,
    onUiAction: (ConversationListUiAction) -> Unit,
    onNavigateToSettingsScreen: () -> Unit,
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val defaultDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo)
    val isExpanded = windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(800)
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

    val partitions = scaffoldDirective.maxHorizontalPartitions
    LaunchedEffect(partitions) {
        if (partitions > 1) {
            // This ensures the Detail role is added to the active visible roles
            scaffoldNavigator.navigateTo(
                ListDetailPaneScaffoldRole.Detail,
                uiState.selectedConversationId,
            )
        }
    }

    // Notify parent about detail pane visibility in compact view
    LaunchedEffect(showingOnlyDetail) { onDetailPaneVisibilityChanged(showingOnlyDetail) }

    // Clear selectedConversationId when user navigates back to list in compact mode
    // This ensures that when returning to the screen, it shows the list instead of detail
    LaunchedEffect(isListPaneHidden, isDetailPaneVisible) {
        if (scaffoldDirective.maxHorizontalPartitions == 1 &&
            !isListPaneHidden &&
            !isDetailPaneVisible &&
            uiState.selectedConversationId != null
        ) {
            // User is back on list in compact mode, clear the selection
            onUiAction(ConversationListUiAction.ClearSelection)
        }
    }

    // Restore detail pane when returning to this screen ONLY if we have a selected conversation
    LaunchedEffect(Unit) {
        val selectedId = uiState.selectedConversationId
        // Only restore if we have a selected conversation AND we're in compact mode
        if (selectedId != null && scaffoldDirective.maxHorizontalPartitions == 1) {
            // Re-navigate to ensure the detail is properly loaded
            scaffoldNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedId)
        }
    }

    // When selected conversation changes, navigate to detail
    LaunchedEffect(uiState.selectedConversationId) {
        uiState.selectedConversationId?.let {
            scope.launch {
                scaffoldNavigator.navigateTo(
                    ListDetailPaneScaffoldRole.Detail, it
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    BackHandler(scaffoldNavigator.canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) {
        scope.launch {
            if (uiState.fullScreenOverlay != null) {
                onUiAction(ConversationListUiAction.CloseFullScreenOverlay)
            } else {
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
                    ConversationListPane(
                        listContent = uiState.conversationsContent,
                        selectedConversationId = scaffoldNavigator.currentDestination?.contentKey,
                        filterByUnread = uiState.filterByUnread,
                        isSearchActive = uiState.isSearchActive,
                        searchTextState = conversationSearchTextFieldState,
                        onProfileClick = onNavigateToSettingsScreen,
                        onUiAction = onUiAction,
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    uiState.selectedConversationId?.let { conversationId ->
                        val conversation =
                            uiState.activeConversations.find { it.id == conversationId }
                        if (conversation != null) {
                            key(conversation.id) {
                                ConversationMessagesPane(
                                    conversation = conversation,
                                    textFieldState = messageInputTextFieldState,
                                    messages = uiState.currentConversationMessages,
                                    isLoadingNewMessage = uiState.loadingNewMessage,
                                    fullScreenOverlay = uiState.fullScreenOverlay,
                                    savedScrollPosition = uiState.conversationScrollPosition,
                                    showBackButton = scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden,
                                    onBackClick = {
                                        scope.launch {
                                            scaffoldNavigator.navigateBack(backNavigationBehavior)
                                        }
                                    },
                                    onUiAction = onUiAction,
                                    currentOdinId = uiState.currentOdinId,
                                    replyToMessage = uiState.replyToMessage,
                                    messageReactions = uiState.messageReactions
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
                    } ?: EmptyDetailPane(
                        title = stringResource(
                            MR.string.chat_select_a_conversation
                        ), subtitle = stringResource(
                            MR.string.chat_select_a_conversation_subtitle
                        )
                    )
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
            paneExpansionDragHandle = { state ->
                val interactionSource = remember { MutableInteractionSource() }
                VerticalDragHandle(
                    modifier = Modifier.paneExpansionDraggable(
                        state, LocalMinimumInteractiveComponentSize.current, interactionSource
                    ), interactionSource = interactionSource
                )
            })
    }
}

@Preview
@Composable
fun ChatListUiPreview() {
    HomebaseTheme {
        ChatListUi(
            snackbarHostState = SnackbarHostState(),
            uiState = ConversationListUiState(),
            conversationSearchTextFieldState = TextFieldState(),
            messageInputTextFieldState = RichTextState(),
            onUiAction = {},
            onNavigateToSettingsScreen = {},
        )
    }
}
