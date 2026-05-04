package id.homebase.chat.conversationlist

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * How long the guard waits for drive-sync to land a conversation referenced by
 * a `rememberSaveable`-restored Detail destination before popping the pane
 * scaffold back to List. Long enough that a sync about to land doesn't lose
 * its rendering window; short enough that the user doesn't sit on the
 * "select a conversation" empty pane noticeably.
 */
internal val COLD_START_DETAIL_GRACE = 800.milliseconds

/**
 * Cold-start guard for the chat list's `ListDetailPaneScaffold`.
 *
 * The pane scaffold's destination history is `rememberSaveable`-backed and
 * survives process death. After Android process death + recents resume, on
 * phone (single-pane), the scaffold can re-mount with a Detail destination
 * whose `contentKey` references a conversation that hasn't yet been loaded
 * by drive sync. The user sees the EmptyDetailPane ("select a conversation")
 * full-screen until the conversation appears or some other path navigates
 * away — observed at 2+ seconds in homebase.log around 2026-05-01 08:05:49.
 *
 * This guard gives drive sync [COLD_START_DETAIL_GRACE] to land the target,
 * then pops back to List if the conversation still isn't present. Two-pane
 * mode is exempted because Detail-without-selection is legitimate UX there.
 *
 * **Keying note.** The effect re-keys on the derived [Boolean]
 * `isRestoredConvLoaded`, NOT on the underlying conversation set. Drive-sync
 * arrives in chunks during cold-start; each chunk produces a new collection,
 * which would reset the timer if we keyed on it directly. Keying on the
 * Boolean means the effect re-launches only when the target conversation
 * actually appears in the set (`false` → `true`), at which point the
 * early-return prevents the pop — exactly the desired behavior.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ColdStartDetailGuard(
    scaffoldNavigator: ThreePaneScaffoldNavigator<Uuid>,
    loadedConversationIds: Set<Uuid>,
    maxHorizontalPartitions: Int,
) {
    val restoredDetailKey = scaffoldNavigator.currentDestination
        ?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }
        ?.contentKey
    val isRestoredConvLoaded =
        restoredDetailKey != null && restoredDetailKey in loadedConversationIds

    LaunchedEffect(restoredDetailKey, isRestoredConvLoaded, maxHorizontalPartitions) {
        if (maxHorizontalPartitions > 1) return@LaunchedEffect
        if (restoredDetailKey == null) return@LaunchedEffect
        if (isRestoredConvLoaded) return@LaunchedEffect
        delay(COLD_START_DETAIL_GRACE)
        // Re-check after the wait. If the conversation landed during the delay
        // the keying flip would have cancelled this coroutine — this re-read
        // defends against the small race where the cancellation hasn't yet
        // reached us when we resume.
        if (restoredDetailKey !in loadedConversationIds &&
            scaffoldNavigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail
        ) {
            Logger.i(tag = "ConversationListUi") {
                "Detail pane restored with unloaded conversation $restoredDetailKey — popping to List"
            }
            scaffoldNavigator.navigateBack(BackNavigationBehavior.PopUntilContentChange)
        }
    }
}
