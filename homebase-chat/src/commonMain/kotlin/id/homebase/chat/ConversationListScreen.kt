package id.homebase.chat

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.ConversationListPane
import id.homebase.chat.widget.ConversationMessagesPane
import id.homebase.chat.widget.EmptyDetailPane
import id.homebase.chat.widget.NewConversationPane
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.chat_select_a_conversation
import id.homebase.resources.chat_select_a_conversation_subtitle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun ConversationListScreen(
    viewModel: ChatListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettingsScreen: () -> Unit,
    onDetailPaneVisibilityChanged: (Boolean) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        when (uiState.uiEvent) {
            ConversationListUiEvent.NavigateBack -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            null -> {}
        }
    }

    ChatListUi(
        uiState = uiState,
        onUiAction = viewModel::onAction,
        onNavigateToSettingsScreen = onNavigateToSettingsScreen,
        onDetailPaneVisibilityChanged = onDetailPaneVisibilityChanged
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatListUi(
    uiState: ConversationListUiState,
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
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List),
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail)
            )
        } else {
            listOf(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List))
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
    LaunchedEffect(showingOnlyDetail) {
        onDetailPaneVisibilityChanged(showingOnlyDetail)
    }

    BackHandler(scaffoldNavigator.canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) {
        scope.launch {
            scaffoldNavigator.navigateBack(BackNavigationBehavior.PopUntilContentChange)
        }
    }

    ListDetailPaneScaffold(
        modifier = Modifier.fillMaxSize(),
        directive = scaffoldNavigator.scaffoldDirective,
        scaffoldState = scaffoldNavigator.scaffoldState,
        listPane = {
            AnimatedPane(
                modifier = Modifier
            ) {
                if (uiState.showingNewChatPane) {
                    NewConversationPane(
                        contacts = uiState.contacts,
                        searchQuery = uiState.searchQuery,
                        onBackClick = { onUiAction(ConversationListUiAction.BackToListClicked) },
                        onContactClick = { contact ->
                            onUiAction(ConversationListUiAction.ContactClicked(contact))
                        },
                        onSearchQueryChanged = { query ->
                            onUiAction(ConversationListUiAction.SearchQueryChanged(query))
                        })
                } else {
                    ConversationListPane(
                        conversations = uiState.conversations,
                        selectedConversationId = scaffoldNavigator.currentDestination?.contentKey,
                        onConversationClick = { conversationId ->
                            onUiAction(ConversationListUiAction.ConversationClicked(conversationId))
                            scope.launch {
                                scaffoldNavigator.navigateTo(
                                    ListDetailPaneScaffoldRole.Detail, conversationId
                                )
                            }
                        },
                        onProfileClick = onNavigateToSettingsScreen,
                        onUiAction = onUiAction,
                    )
                }
            }
        },
        detailPane = {
            AnimatedPane {
                uiState.selectedConversationId?.let { conversationId ->
                    val conversation = uiState.conversations.find { it.id == conversationId }
                    if (conversation != null) {
                        ConversationMessagesPane(
                            conversation = conversation,
                            messages = uiState.currentConversationMessages,
                            savedScrollPosition = uiState.conversationScrollPosition,
                            showBackButton = scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden,
                            onBackClick = {
                                scope.launch {
                                    scaffoldNavigator.navigateBack(backNavigationBehavior)
                                }
                            },
                            onUiAction = onUiAction,
                            currentOdinId = uiState.currentOdinId
                        )
                    } else {
                        EmptyDetailPane(
                            title = stringResource(MR.string.chat_select_a_conversation),
                            subtitle = stringResource(MR.string.chat_select_a_conversation_subtitle)
                        )
                    }
                } ?: EmptyDetailPane(
                    title = stringResource(MR.string.chat_select_a_conversation),
                    subtitle = stringResource(MR.string.chat_select_a_conversation_subtitle)
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

@Preview
@Composable
fun ChatListUiPreview() {
    HomebaseTheme {
        ChatListUi(uiState = ConversationListUiState(), onNavigateToSettingsScreen = {}, onUiAction = {})
    }
}
