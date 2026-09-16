package id.homebase.chat.conversationlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * #1523: rotating landscape→portrait after a notification tap stranded the app on the empty
 * "Select a conversation" pane — no list, no back, no bottom navigation, force-quit to recover.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3AdaptiveApi::class)
class DetailPaneDeadEndTest {

    @Test
    fun `an empty detail pane owning a compact window is the stranded state`() {
        assertTrue(
            isStrandedOnEmptyDetailPane(
                maxHorizontalPartitions = 1,
                currentPane = ListDetailPaneScaffoldRole.Detail,
                detailContentKey = null,
            )
        )
        // A wiped destination history has no current pane at all and still resolves to
        // Detail-expanded / List-hidden — the exact screen the issue reports.
        assertTrue(
            isStrandedOnEmptyDetailPane(
                maxHorizontalPartitions = 1,
                currentPane = null,
                detailContentKey = null,
            )
        )
        assertFalse(
            isStrandedOnEmptyDetailPane(
                maxHorizontalPartitions = 1,
                currentPane = ListDetailPaneScaffoldRole.Detail,
                detailContentKey = Uuid.random(),
            ),
            "a conversation is on screen with a back button — nothing to recover from",
        )
        assertFalse(
            isStrandedOnEmptyDetailPane(
                maxHorizontalPartitions = 2,
                currentPane = ListDetailPaneScaffoldRole.Detail,
                detailContentKey = null,
            ),
            "the placeholder beside a visible list is the designed two-pane empty state",
        )
        assertFalse(
            isStrandedOnEmptyDetailPane(
                maxHorizontalPartitions = 1,
                currentPane = ListDetailPaneScaffoldRole.List,
                detailContentKey = null,
            )
        )
    }

    @Test
    fun `the bottom bar is only surrendered to a detail pane that has content`() {
        assertTrue(
            detailPaneOwnsWindow(
                isListPaneHidden = true,
                isDetailPaneVisible = true,
                detailContentKey = Uuid.random(),
            )
        )
        assertFalse(
            detailPaneOwnsWindow(
                isListPaneHidden = true,
                isDetailPaneVisible = true,
                detailContentKey = null,
            )
        )
        assertFalse(
            detailPaneOwnsWindow(
                isListPaneHidden = false,
                isDetailPaneVisible = true,
                detailContentKey = Uuid.random(),
            )
        )
    }

    /**
     * The library landmine behind the whole bug: on a two-pane window no earlier destination
     * changes the scaffold value (`ThreePaneScaffoldValue.equals` compares only the three adapted
     * values, and both panes stay Expanded), so `navigateBack()` takes its history-clearing branch.
     */
    @Test
    fun `bare navigateBack empties the destination history on a two-pane window`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val harness = Harness()
            setContent { harness.Content(partitions = 2) }
            waitForIdle()

            harness.dispatch { navigateBack() }
            waitUntil(timeoutMillis = 5_000) { harness.navigator.currentDestination == null }

            assertNull(harness.navigator.currentDestination)
            assertTrue(
                isStrandedOnEmptyDetailPane(1, harness.navigator.currentDestination?.pane, null),
                "a wiped history is one re-layout away from the dead end",
            )
        }

    /**
     * The reported repro, driven through the real [NotificationNavigationEffects]: a notification
     * tap selects a conversation on a two-pane window, then the window rotates to one partition.
     */
    @Test
    fun `a notification tap then a rotation to one pane keeps the conversation and the list`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val harness = Harness()
            var partitions by mutableStateOf(2)
            var selectedId by mutableStateOf<Uuid?>(null)
            setContent { harness.Content(partitions, selectedId) }
            waitForIdle()

            val conversation = Uuid.random()
            selectedId = conversation // the notification tap
            waitUntil(timeoutMillis = 5_000) {
                harness.navigator.currentDestination?.contentKey == conversation
            }
            assertTrue(
                harness.navigator.canNavigateBack(
                    BackNavigationBehavior.PopUntilCurrentDestinationChange
                ),
                "the swap wiped the List entry out of the destination history",
            )

            partitions = 1 // rotate to portrait
            waitForIdle()

            assertEquals(conversation, harness.navigator.currentDestination?.contentKey)
            assertFalse(
                isStrandedOnEmptyDetailPane(
                    maxHorizontalPartitions = 1,
                    currentPane = harness.navigator.currentDestination?.pane,
                    detailContentKey = harness.navigator.currentDestination?.contentKey,
                )
            )

            // And the way out of that full-screen conversation still reaches the list.
            harness.dispatch { returnToListPane() }
            waitUntil(timeoutMillis = 5_000) {
                harness.navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.List
            }
        }

    @Test
    fun `returnToListPane escapes a history with no list entry left in it`() =
        runDesktopComposeUiTest(width = 700, height = 1400) {
            val harness = Harness()
            setContent {
                harness.Content(
                    partitions = 1,
                    initialHistory = listOf(
                        ThreePaneScaffoldDestinationItem(
                            ListDetailPaneScaffoldRole.Detail,
                            Uuid.random(),
                        )
                    ),
                )
            }
            waitForIdle()

            harness.dispatch { navigateBack() } // a one-entry history has no previous index
            waitUntil(timeoutMillis = 5_000) { harness.navigator.currentDestination == null }

            harness.dispatch { returnToListPane() }
            waitUntil(timeoutMillis = 5_000) {
                harness.navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.List
            }
        }

    private class Harness {
        lateinit var navigator: ThreePaneScaffoldNavigator<Uuid>
        lateinit var scope: CoroutineScope

        fun dispatch(block: suspend ThreePaneScaffoldNavigator<Uuid>.() -> Unit) {
            scope.launch { navigator.block() }
        }

        @Composable
        fun Content(
            partitions: Int,
            selectedConversationId: Uuid? = null,
            initialHistory: List<ThreePaneScaffoldDestinationItem<Uuid>> = listOf(
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List),
                ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail),
            ),
        ) {
            scope = rememberCoroutineScope()
            val default = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
            val directive = PaneScaffoldDirective(
                maxHorizontalPartitions = partitions,
                horizontalPartitionSpacerSize = 0.dp,
                maxVerticalPartitions = default.maxVerticalPartitions,
                verticalPartitionSpacerSize = default.verticalPartitionSpacerSize,
                defaultPanePreferredWidth = 360.dp,
                excludedBounds = default.excludedBounds,
            )
            val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator(
                scaffoldDirective = directive,
                initialDestinationHistory = initialHistory,
            )
            navigator = scaffoldNavigator
            NotificationNavigationEffects(
                scaffoldNavigator = scaffoldNavigator,
                selectedConversationId = selectedConversationId,
                scaffoldDirective = directive,
                onClearSelection = {},
            )
            ListDetailPaneScaffold(
                directive = scaffoldNavigator.scaffoldDirective,
                scaffoldState = scaffoldNavigator.scaffoldState,
                listPane = { AnimatedPane { Box(Modifier.fillMaxSize()) } },
                detailPane = { AnimatedPane { Box(Modifier.fillMaxSize()) } },
            )
        }
    }
}
