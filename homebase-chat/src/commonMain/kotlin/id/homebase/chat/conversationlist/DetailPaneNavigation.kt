package id.homebase.chat.conversationlist

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import kotlin.uuid.Uuid

/**
 * The dead end this file exists to make unreachable (#1523).
 *
 * A compact window with the scaffold on Detail and no content key draws the "select a
 * conversation" placeholder full-screen: list hidden, no back affordance, no bottom navigation.
 * An *empty* destination history resolves to exactly that — `primary = Expanded,
 * secondary = Hidden`, `currentDestination = null` — and `canNavigateBack` is false there, so
 * nothing on screen can leave it. Force-quit is the only way out.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun isStrandedOnEmptyDetailPane(
    maxHorizontalPartitions: Int,
    currentPane: ThreePaneScaffoldRole?,
    detailContentKey: Uuid?,
): Boolean =
    maxHorizontalPartitions == 1 &&
        (currentPane == null || currentPane == ListDetailPaneScaffoldRole.Detail) &&
        detailContentKey == null

/** The detail pane may claim the compact window, and hide the bottom bar, only while it has
 *  a conversation to draw. */
internal fun detailPaneOwnsWindow(
    isListPaneHidden: Boolean,
    isDetailPaneVisible: Boolean,
    detailContentKey: Uuid?,
): Boolean = isListPaneHidden && isDetailPaneVisible && detailContentKey != null

/**
 * Leaves the detail pane for the list pane without ever emptying the destination history.
 *
 * Never call the navigator's own `navigateBack()` instead of this: it *clears* the whole history
 * whenever no earlier entry would change the scaffold value — true of a single-entry history, and
 * of every entry while two panes are on screen, since `ThreePaneScaffoldValue.equals` compares only
 * the three adapted values and both panes stay Expanded whichever destination is current.
 *
 * `PopUntilCurrentDestinationChange` drops every Detail entry down to the most recent List one, so
 * one pop reaches the list and leaves no stale Detail on top for a back press to re-enter. With no
 * List entry to fall back on, pushing one is the only escape that avoids that clearing branch.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal suspend fun ThreePaneScaffoldNavigator<Uuid>.returnToListPane() {
    if (canNavigateBack(BackNavigationBehavior.PopUntilCurrentDestinationChange)) {
        navigateBack(BackNavigationBehavior.PopUntilCurrentDestinationChange)
    } else {
        navigateTo(ListDetailPaneScaffoldRole.List)
    }
}
