package id.homebase.chat.conversationlist

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Rotating landscape→portrait after a notification tap stranded the app on the empty
 * "Select a conversation" pane — no list, no back, no bottom navigation, force-quit to recover
 * (#1523). The scaffold now derives its layout from the open conversation alone (#1537), so these
 * tests assert that screen has no input that produces it, rather than that a repair undoes it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3AdaptiveApi::class)
class DetailPaneDeadEndTest {

    private val convA = Uuid.parse("cc0577d2-2c92-4843-b08d-166e05ad4c19")
    private val convB = Uuid.parse("37c7d258-e56c-4446-bf6c-0fcc12862577")

    private fun directive(partitions: Int) = PaneScaffoldDirective(
        maxHorizontalPartitions = partitions,
        horizontalPartitionSpacerSize = 0.dp,
        maxVerticalPartitions = 1,
        verticalPartitionSpacerSize = 0.dp,
        defaultPanePreferredWidth = 360.dp,
        excludedBounds = emptyList(),
    )

    @Test
    fun no_width_and_selection_pair_strands_an_empty_detail_pane() {
        for (partitions in 1..2) {
            for (selected in listOf(null, convA)) {
                val value = chatScaffoldValue(directive(partitions), selected)
                val listHidden = value[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
                val detailShown =
                    value[ListDetailPaneScaffoldRole.Detail] != PaneAdaptedValue.Hidden
                if (listHidden && detailShown) {
                    assertEquals(
                        convA,
                        selected,
                        "partitions=$partitions selected=$selected strands the window on a " +
                            "detail pane with no conversation to draw and no list to go back to",
                    )
                }
            }
        }
    }

    @Test
    fun the_four_layouts() {
        val compactList = chatScaffoldValue(directive(1), null)
        assertEquals(PaneAdaptedValue.Expanded, compactList[ListDetailPaneScaffoldRole.List])
        assertEquals(PaneAdaptedValue.Hidden, compactList[ListDetailPaneScaffoldRole.Detail])

        val compactDetail = chatScaffoldValue(directive(1), convA)
        assertEquals(PaneAdaptedValue.Hidden, compactDetail[ListDetailPaneScaffoldRole.List])
        assertEquals(PaneAdaptedValue.Expanded, compactDetail[ListDetailPaneScaffoldRole.Detail])

        // A single destination still fills both partitions on a wide window — the placeholder
        // beside a visible list is the designed two-pane empty state, not the dead end.
        val wideEmpty = chatScaffoldValue(directive(2), null)
        assertEquals(PaneAdaptedValue.Expanded, wideEmpty[ListDetailPaneScaffoldRole.List])
        assertEquals(PaneAdaptedValue.Expanded, wideEmpty[ListDetailPaneScaffoldRole.Detail])

        val wideDetail = chatScaffoldValue(directive(2), convA)
        assertEquals(PaneAdaptedValue.Expanded, wideDetail[ListDetailPaneScaffoldRole.List])
        assertEquals(PaneAdaptedValue.Expanded, wideDetail[ListDetailPaneScaffoldRole.Detail])
    }

    @Test
    fun shrinking_to_one_partition_follows_the_selection() {
        // The reported repro: a notification tap on a two-pane window, then a rotation to one
        // partition. Shrinking cannot leave a pane on screen that portrait has no room for,
        // because the layout is recomputed from the same selection the wide one used.
        val beforeBackOut = chatScaffoldValue(directive(2), convA)
        assertEquals(PaneAdaptedValue.Expanded, beforeBackOut[ListDetailPaneScaffoldRole.Detail])

        val rotated = chatScaffoldValue(directive(1), null)
        assertEquals(PaneAdaptedValue.Expanded, rotated[ListDetailPaneScaffoldRole.List])
        assertEquals(PaneAdaptedValue.Hidden, rotated[ListDetailPaneScaffoldRole.Detail])
    }

    @Composable
    private fun Host(partitions: Int, selected: Uuid?) {
        val directive = directive(partitions)
        ListDetailPaneScaffold(
            directive = directive,
            value = chatScaffoldValue(directive, selected),
            listPane = { AnimatedPane { Text("list") } },
            detailPane = { AnimatedPane { Text("detail=${selected?.toString() ?: "none"}") } },
        )
    }

    @Test
    fun compact_cold_start_into_a_conversation_shows_it() = runComposeUiTest {
        // The notification-tap path: ChatList is composed with a selection already set.
        setContent { MaterialTheme { Host(partitions = 1, selected = convA) } }
        waitForIdle()

        onNodeWithText("detail=$convA").assertExists()
        onNodeWithText("list").assertDoesNotExist()
    }

    @Test
    fun compact_with_no_selection_never_shows_the_placeholder() = runComposeUiTest {
        setContent { MaterialTheme { Host(partitions = 1, selected = null) } }
        waitForIdle()

        onNodeWithText("list").assertExists()
        onNodeWithText("detail=none").assertDoesNotExist()
    }

    @Test
    fun compact_warm_swap_then_back_to_list() = runComposeUiTest {
        var selected by mutableStateOf<Uuid?>(null)
        setContent { MaterialTheme { Host(partitions = 1, selected = selected) } }
        waitForIdle()
        onNodeWithText("list").assertExists()

        selected = convA
        waitForIdle()
        onNodeWithText("detail=$convA").assertExists()

        // Warm notification tap for B while the user is on A.
        selected = convB
        waitForIdle()
        onNodeWithText("detail=$convB").assertExists()

        // User back-to-list clears the selection, and that is the whole of it.
        selected = null
        waitForIdle()
        onNodeWithText("list").assertExists()
    }

    @Test
    fun expanded_keeps_the_list_while_swapping_the_detail() = runComposeUiTest {
        var selected by mutableStateOf<Uuid?>(convA)
        setContent { MaterialTheme { Host(partitions = 2, selected = selected) } }
        waitForIdle()
        onNodeWithText("list").assertExists()
        onNodeWithText("detail=$convA").assertExists()

        selected = convB
        waitForIdle()
        onNodeWithText("list").assertExists()
        onNodeWithText("detail=$convB").assertExists()
    }

    @Test
    fun rotating_with_a_conversation_open_keeps_it() = runComposeUiTest {
        var partitions by mutableStateOf(2)
        setContent { MaterialTheme { Host(partitions = partitions, selected = convA) } }
        waitForIdle()
        onNodeWithText("detail=$convA").assertExists()

        partitions = 1
        waitForIdle()
        onNodeWithText("detail=$convA").assertExists()
        onNodeWithText("list").assertDoesNotExist()
    }

    @Test
    fun rotating_with_nothing_open_keeps_the_list() = runComposeUiTest {
        var partitions by mutableStateOf(2)
        setContent { MaterialTheme { Host(partitions = partitions, selected = null) } }
        waitForIdle()
        onNodeWithText("list").assertExists()

        partitions = 1
        waitForIdle()
        onNodeWithText("list").assertExists()
        onNodeWithText("detail=none").assertDoesNotExist()
    }
}
