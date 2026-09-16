package id.homebase.chat.conversationlist

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import kotlin.uuid.Uuid

/**
 * An empty destination history resolves to Detail-Expanded / List-Hidden with a null
 * `currentDestination`: the "select a conversation" placeholder full-screen, list gone, and
 * `canNavigateBack` false, so nothing on screen can leave it.
 *
 * Only a *null* content key qualifies. A key whose conversation has not synced yet belongs to
 * [ColdStartDetailGuard]; widening this test flickers the bottom bar across that guard's grace
 * period on every cold start into a chat.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun isStrandedOnEmptyDetailPane(
    maxHorizontalPartitions: Int,
    currentDestination: ThreePaneScaffoldDestinationItem<Uuid>?,
): Boolean =
    maxHorizontalPartitions == 1 &&
        (currentDestination == null ||
            currentDestination.pane == ListDetailPaneScaffoldRole.Detail) &&
        currentDestination?.contentKey == null

/**
 * Leaves the detail pane for the list pane without ever emptying the destination history (#1523).
 *
 * The navigator's own `navigateBack()` *clears* the whole history whenever no earlier entry would
 * change the scaffold value: true of a one-entry history, and of every entry while two panes are on
 * screen, since `ThreePaneScaffoldValue.equals` compares only the three adapted values. Pushing a
 * List entry is the only escape once there is none left to pop to. `PopUntilCurrentDestinationChange`
 * drops every Detail down to the most recent List, leaving no stale entry for back to re-enter.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal suspend fun ThreePaneScaffoldNavigator<Uuid>.returnToListPane() {
    if (canNavigateBack(BackNavigationBehavior.PopUntilCurrentDestinationChange)) {
        navigateBack(BackNavigationBehavior.PopUntilCurrentDestinationChange)
    } else {
        navigateTo(ListDetailPaneScaffoldRole.List)
    }
}

/**
 * Closes one detail entry, stopping at the first destination whose content differs — not
 * interchangeable with [returnToListPane], which pops every Detail down to the most recent List.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal suspend fun ThreePaneScaffoldNavigator<Uuid>.closeDetailEntry() {
    if (canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) {
        navigateBack(BackNavigationBehavior.PopUntilContentChange)
    }
}
