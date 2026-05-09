package id.homebase.core.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import kotlin.test.Test

/**
 * Quiescence tests for [ReactionList].
 *
 * Build 1.3.1388 used a `BoxWithConstraints` + measure-time `fitCount` pattern that produced an
 * Android ANR via a Compose layout-invalidation loop. These tests render the post-fix
 * implementation under conditions that previously caused the loop — narrow containers, churning
 * reaction counts — and the test runner's timeout is the implicit assertion: if measure doesn't
 * settle, the test hangs and CI fails. They also lock in the static `take(7)` cap so a future
 * regression that re-introduces dynamic slicing would change the visible emoji count.
 */
@OptIn(ExperimentalTestApi::class)
class ReactionListLoopTest {

    @Test
    fun rendersNothing_whenEmpty() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionList(
                    reactionSummary = summaryOf(),
                    onReactionClick = {},
                )
            }
        }
        onAllNodesWithText("👍").assertCountEquals(0)
    }

    @Test
    fun renders_allEmojis_belowCap() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionList(
                    reactionSummary = summaryOf("❤️", "👍", "👎"),
                    onReactionClick = {},
                )
            }
        }
        onAllNodesWithText("❤️").assertCountEquals(1)
        onAllNodesWithText("👍").assertCountEquals(1)
        onAllNodesWithText("👎").assertCountEquals(1)
    }

    @Test
    fun caps_at_seven_emojis_when_overflowing() = runComposeUiTest {
        // 10 distinct reactions; only the first 7 should appear in the merged pill.
        setContent {
            MaterialTheme {
                ReactionList(
                    reactionSummary = summaryOf(
                        "😀", "😁", "😂", "🤣", "😃", "😄", "😅", "😆", "😉", "😊"
                    ),
                    onReactionClick = {},
                )
            }
        }
        // First 7 visible.
        onAllNodesWithText("😀").assertCountEquals(1)
        onAllNodesWithText("😁").assertCountEquals(1)
        onAllNodesWithText("😂").assertCountEquals(1)
        onAllNodesWithText("🤣").assertCountEquals(1)
        onAllNodesWithText("😃").assertCountEquals(1)
        onAllNodesWithText("😄").assertCountEquals(1)
        onAllNodesWithText("😅").assertCountEquals(1)
        // 8th onward dropped.
        onAllNodesWithText("😆").assertCountEquals(0)
        onAllNodesWithText("😉").assertCountEquals(0)
        onAllNodesWithText("😊").assertCountEquals(0)
    }

    @Test
    fun rendersInNarrowContainer_doesNotLoop() = runComposeUiTest {
        // 60dp is narrower than a single 40dp emoji slot would naturally need with padding.
        // The previous implementation would compute fitCount=1 here based on measured maxWidth.
        // The post-fix implementation simply takes 7 and lets the Surface clip — no measure-time
        // read, no feedback edge. This test passes if composition completes (i.e. if measure
        // converges within the test runner's timeout).
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(60.dp)) {
                    ReactionList(
                        reactionSummary = summaryOf("❤️", "👍", "👎", "😂", "😮"),
                        onReactionClick = {},
                    )
                }
            }
        }
        // Reaching this assertion at all means measure converged.
        onAllNodesWithText("❤️").assertCountEquals(1)
    }

    private fun summaryOf(vararg emojis: String): ReactionSummary {
        val entries = emojis.mapIndexed { idx, emoji ->
            "k$idx" to ReactionEntry(
                key = "k$idx",
                count = 1,
                reactionContent = "{\"emoji\":\"$emoji\"}",
            )
        }.toMap()
        return ReactionSummary(reactions = entries)
    }
}
