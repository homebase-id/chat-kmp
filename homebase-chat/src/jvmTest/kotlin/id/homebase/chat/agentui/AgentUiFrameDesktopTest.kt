package id.homebase.chat.agentui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.onSecondaryPress
import kotlin.test.Test
import java.awt.datatransfer.DataFlavor
import kotlin.test.assertEquals

// On a pointer device a card behaves like a text bubble: no whole-card click (no highlight),
// right-click reaches the message menu around it, and its controls keep their own clicks.
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class AgentUiFrameDesktopTest {
    @Test fun card_takes_no_gesture_of_its_own_and_right_click_reaches_the_message_menu() = runComposeUiTest {
        var menus = 0
        val actions = mutableListOf<String>()
        val tree = AgentUiFixtures.decodeDraftPostCard()
        setContent {
            HomebaseTheme(darkTheme = false) {
                Box(Modifier.onSecondaryPress { menus++ }) {
                    AgentUiFrame(sentByYou = false, authorName = "Agent", footer = { Text("10:42") }) {
                        AgentUiTreeView(
                            tree = tree,
                            inputs = remember { AgentUiInputState(tree.inputs) },
                            imageData = { null },
                            enabled = true,
                            pendingActionIds = emptySet(),
                            onAction = { actions += it.actionId },
                            onLongClick = {},
                        )
                    }
                }
            }
        }
        // Only the three buttons are clickable; the frame itself is not.
        onAllNodes(hasClickAction()).assertCountEquals(3)
        onNodeWithText("Agent").assertExists()
        onNodeWithText("10:42").assertExists()

        onNodeWithText("Approve").performMouseInput { rightClick() }
        assertEquals(1, menus)
        onNodeWithText("Agent").performMouseInput { rightClick() }
        assertEquals(2, menus)
        assertEquals(emptyList(), actions)

        onNodeWithText("Approve").performClick()
        assertEquals(listOf("approve"), actions)
    }

    // A copy button puts its exact text on the clipboard, and works on your own card where actions are off.
    @Test fun copy_button_copies_its_text_on_your_own_card() = runComposeUiTest {
        val copied = mutableListOf<ClipEntry?>()
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = copied.lastOrNull()
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                copied += clipEntry
            }
            override val nativeClipboard: NativeClipboard get() = error("no native clipboard in tests")
        }
        val tree = AgentUiFixtures.valid(AgentUiFixtures.tree("""{"id":"root","type":"button","label":"Copy code","copy":"ABC-123"}"""))
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                AgentUiTreeView(
                    tree = tree,
                    inputs = remember { AgentUiInputState(tree.inputs) },
                    imageData = { null },
                    enabled = false,
                    pendingActionIds = emptySet(),
                    onAction = {},
                    copyEnabled = true,
                )
            }
        }
        onNodeWithText("Copy code").performClick()
        waitForIdle()
        assertEquals(listOf("ABC-123"), copied.map { it?.nativeClipEntry?.let { t -> (t as java.awt.datatransfer.Transferable).getTransferData(DataFlavor.stringFlavor) } })
    }
}
