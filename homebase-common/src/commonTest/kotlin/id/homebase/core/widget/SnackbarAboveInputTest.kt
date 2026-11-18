package id.homebase.core.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/** A snackbar never covers the input people type into: it rides above a marked input, and falls back to the bottom. */
@OptIn(ExperimentalTestApi::class)
class SnackbarAboveInputTest {
    private fun showsAboveInput(withInput: Boolean) = runComposeUiTest {
        var inputShown by mutableStateOf(withInput)
        val host = SnackbarHostState()
        setContent {
            MaterialTheme {
                SnackbarAboveInput(host) {
                    if (inputShown) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(80.dp).keepSnackbarsAbove().testTag("input"))
                    }
                    LaunchedEffect(Unit) { host.showSnackbar("Saved to Pictures", duration = SnackbarDuration.Indefinite) }
                }
            }
        }
        waitForIdle()
        val snackbar = onNodeWithText("Saved to Pictures").getBoundsInRoot()
        if (withInput) {
            val input = onNodeWithTag("input").getBoundsInRoot()
            assertTrue(snackbar.bottom <= input.top, "the snackbar (bottom ${snackbar.bottom}) stays above the input (top ${input.top})")

            inputShown = false
            waitForIdle()
            val root = onRoot().getBoundsInRoot()
            val moved = onNodeWithText("Saved to Pictures").getBoundsInRoot()
            assertTrue(moved.bottom > input.top, "with the input gone the snackbar drops to the bottom (${moved.bottom} of ${root.bottom})")
        } else {
            val root = onRoot().getBoundsInRoot()
            assertTrue(root.bottom - snackbar.bottom < 40.dp, "with no input the snackbar sits at the bottom (${snackbar.bottom} of ${root.bottom})")
        }
    }

    @Test
    fun aSnackbarStaysAboveAMarkedInputAndDropsWhenItGoes() = showsAboveInput(withInput = true)

    @Test
    fun withNoInputASnackbarSitsAtTheBottom() = showsAboveInput(withInput = false)
}
