package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class FilesyncOpenDialogTest {

    private val node = FilesyncNode(Uuid.random(), Uuid.random(), isFolder = false, name = "report.pdf", modifiedMs = 1, sizeBytes = 5)

    @Test
    fun aDoubleClickOnANameLeavesTheFileDialogOpen() = opening { doubleClick() }

    @Test
    fun aClickOnANameLeavesTheFileDialogOpen() = opening { click() }

    private fun opening(gesture: MouseInjectionScope.() -> Unit) = runComposeUiTest {
        var asking by mutableStateOf(false)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    TableRow(
                        node = node,
                        selected = false,
                        progress = null,
                        columns = TableColumns(modified = true, kind = true),
                        actions = FilesyncBrowserActions(onAction = { if (it is FilesyncUiAction.NodeClicked) asking = true }, onAskRestore = {}),
                        onSelect = {},
                        selecting = false,
                        inGroup = false,
                    )
                    if (asking) FilesyncShareSheet(listOf(node), onShare = {}, onDismiss = { asking = false })
                }
            }
        }
        onNodeWithText(node.name, useUnmergedTree = true).performMouseInput(gesture)
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertTrue(asking, "the dialog the name opened was closed by the same gesture")
    }
}
