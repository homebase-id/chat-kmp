package id.homebase.core.ui.screens.filesync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class FilesyncTableRowTest {

    private val node = FilesyncNode(Uuid.random(), Uuid.random(), isFolder = false, name = "report.pdf", modifiedMs = 1, sizeBytes = 5)
    private val folder = FilesyncNode(Uuid.random(), Uuid.random(), isFolder = true, name = "Photos", modifiedMs = null, sizeBytes = null)

    private fun recorded(node: FilesyncNode = this.node, block: ComposeUiTest.() -> Unit): List<FilesyncUiAction> {
        val actions = mutableListOf<FilesyncUiAction>()
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    TableRow(
                        node = node,
                        selected = false,
                        progress = null,
                        columns = TableColumns(modified = true, kind = true),
                        actions = FilesyncBrowserActions(onAction = { actions += it }, onAskRestore = {}),
                        onSelect = { actions += FilesyncUiAction.Select(node.nodeId) },
                    )
                }
            }
            block()
            waitForIdle()
        }
        return actions
    }

    @Test
    fun aMouseClickSelectsAndDoesNotOpen() {
        val actions = recorded { onNodeWithText(node.name).performMouseInput { click() } }
        assertEquals(listOf<FilesyncUiAction>(FilesyncUiAction.Select(node.nodeId)), actions)
    }

    @Test
    fun aMouseDoubleClickOpens() {
        val actions = recorded { onNodeWithText(node.name).performMouseInput { doubleClick() } }
        assertEquals(FilesyncUiAction.NodeClicked(node), actions.last())
        assertEquals(1, actions.count { it is FilesyncUiAction.NodeClicked })
    }

    @Test
    fun aClickBesideTheRowsButtonsSelects() {
        val actions = recorded(folder) { onRoot().performMouseInput { click(Offset(width - 70.dp.toPx(), centerY)) } }
        assertEquals(listOf<FilesyncUiAction>(FilesyncUiAction.Select(folder.nodeId)), actions)
    }

    @Test
    fun aClickOnSaveSavesWithoutSelecting() {
        val actions = recorded {
            onRoot().performMouseInput { moveTo(center) }
            waitForIdle()
            onNodeWithContentDescription("Save to device").performMouseInput { click() }
        }
        assertEquals(listOf<FilesyncUiAction>(FilesyncUiAction.SaveClicked(node)), actions)
    }

    @Test
    fun aTapOpensWithoutSelecting() {
        val actions = recorded { onNodeWithText(node.name).performTouchInput { click() } }
        assertEquals(listOf<FilesyncUiAction>(FilesyncUiAction.NodeClicked(node)), actions)
    }
}
