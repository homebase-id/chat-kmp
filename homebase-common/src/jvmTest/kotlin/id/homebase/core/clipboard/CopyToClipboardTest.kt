package id.homebase.core.clipboard

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class CopyToClipboardTest {

    private class RecordingClipboard : Clipboard {
        var entry: ClipEntry? = null
        override val nativeClipboard = java.awt.datatransfer.Clipboard("test")
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
        fun text(): String? =
            (entry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }

    @Test
    fun copyWritesTheTextAndShowsTheSnackbar() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    val host = remember { SnackbarHostState() }
                    val copy = rememberCopyToClipboard(host)
                    Scaffold(snackbarHost = { SnackbarHost(host) }) {
                        Button(onClick = { copy("secret-value") }) { Text("go") }
                    }
                }
            }
        }
        onNodeWithText("go").performClick()
        waitForIdle()
        assertEquals("secret-value", clipboard.text())
        onNodeWithText("Copied to clipboard").assertExists()
    }
}
