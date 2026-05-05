package id.homebase.chat.conversationlist

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import kotlin.uuid.Uuid

/**
 * Installs the two coupled `LaunchedEffect`s that drive notification-tap navigation
 * between the list and detail panes.
 *
 * Effect 1 (cleanup): in compact single-pane layouts, when the scaffold transitions
 * back to list-only *after the detail pane has been visible* and a conversation is
 * still selected, dispatch `ClearSelection`. This is the user-back-to-list path.
 * The `hadDetailVisible` latch is required because the natural entry state of a
 * fresh compact scaffold is also "list visible, detail hidden" — without the latch,
 * Effect 1 fires on initial composition and races Effect 2 when ChatList is brought
 * forward by `AppNavHost.popBackStack(ChatList)` after a notification tap.
 *
 * Effect 2 (swap): when `selectedConversationId` changes to a new value, navigate the
 * scaffold to `Detail(selectedId)`. If the scaffold is already at `Detail` with a stale
 * `contentKey`, pop first so `navigateTo` is not treated as a no-op.
 *
 * Contract with [ConversationListViewModel.selectConversation]: the VM MUST update
 * `selectedConversationId` synchronously when a conversation is selected, *before*
 * any message-stream collect resumes. If that flip is delayed until the first
 * `ChatMessagesData.Messages` arrives, this effect won't fire and a slow DB read on
 * cold-start / post-reconnect will hold notification-tap navigation hostage for
 * seconds. See `loadMessagesForConversation` for the prelude that enforces this.
 *
 * The two effects race during a programmatic swap: `navigateBack()` transits the
 * scaffold through the "list-only" state that effect 1 watches for. `isSwappingDetailPane`
 * gates effect 1 during that window so it doesn't null out `selectedConversationId`
 * mid-swap.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun NotificationNavigationEffects(
    scaffoldNavigator: ThreePaneScaffoldNavigator<Uuid>,
    selectedConversationId: Uuid?,
    scaffoldDirective: PaneScaffoldDirective,
    onClearSelection: () -> Unit,
) {
    val isListPaneHidden =
        scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    val isDetailPaneVisible =
        scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden

    var isSwappingDetailPane by remember { mutableStateOf(false) }
    var hadDetailVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isListPaneHidden, isDetailPaneVisible) {
        if (isDetailPaneVisible) hadDetailVisible = true
        if (!isSwappingDetailPane && scaffoldDirective.maxHorizontalPartitions == 1 && !isListPaneHidden && !isDetailPaneVisible && hadDetailVisible && selectedConversationId != null) {
            Logger.i(tag = "ConversationListUi") { "ClearSelection fired (compact back-to-list) selectedId=$selectedConversationId" }
            onClearSelection()
        }
    }

    var lastNavigatedConversationId by remember { mutableStateOf<Uuid?>(null) }
    LaunchedEffect(selectedConversationId) {
        val selectedId = selectedConversationId
        if (selectedId != null && selectedId != lastNavigatedConversationId) {
            Logger.i(tag = "ConversationListUi") { "Restore detail pane for $selectedId" }
            lastNavigatedConversationId = selectedId
            val cur = scaffoldNavigator.currentDestination
            try {
                if (cur?.pane == ListDetailPaneScaffoldRole.Detail && cur.contentKey != selectedId) {
                    Logger.i(tag = "ConversationListUi") { "Swapping detail pane ${cur.contentKey}->$selectedId" }
                    isSwappingDetailPane = true
                    try {
                        scaffoldNavigator.navigateBack()
                        scaffoldNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedId)
                    } finally {
                        isSwappingDetailPane = false
                    }
                } else {
                    scaffoldNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedId)
                }
                Logger.i(tag = "ConversationListUi") { "Restore detail pane completed for $selectedId" }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Effect re-keys when selectedConversationId changes; cancellation here means
                // navigateTo() was interrupted before settling. Surfacing this is critical for
                // diagnosing notification-tap navigation regressions: a silent cancel looks
                // identical to success in the log otherwise.
                Logger.w(tag = "ConversationListUi") { "Restore detail pane CANCELLED for $selectedId (selectedConversationId changed mid-navigateTo)" }
                throw e
            }
        } else if (selectedId == null) {
            lastNavigatedConversationId = null
        }
    }
}
