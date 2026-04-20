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
 * Effect 1 (cleanup): in compact single-pane layouts, when the scaffold transitions to
 * list-only while a conversation is still selected, dispatch `ClearSelection`. This is
 * the user-back-to-list path.
 *
 * Effect 2 (swap): when `selectedConversationId` changes to a new value, navigate the
 * scaffold to `Detail(selectedId)`. If the scaffold is already at `Detail` with a stale
 * `contentKey`, pop first so `navigateTo` is not treated as a no-op.
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
    LaunchedEffect(isListPaneHidden, isDetailPaneVisible) {
        if (!isSwappingDetailPane && scaffoldDirective.maxHorizontalPartitions == 1 && !isListPaneHidden && !isDetailPaneVisible && selectedConversationId != null) {
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
        } else if (selectedId == null) {
            lastNavigatedConversationId = null
        }
    }
}
