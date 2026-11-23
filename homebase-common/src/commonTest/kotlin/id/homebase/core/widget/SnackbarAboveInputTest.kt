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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/** A snackbar never covers the input people type into: it rides above a marked input, and sits at the bottom without one. */
@OptIn(ExperimentalTestApi::class)
class SnackbarAboveInputTest {
    private val message = "Saved to Pictures"

    // The text sits inside the snackbar's own padding, so "just above" means within a snackbar's height.
    private val snackbarHeight = 80.dp

    private fun ComposeUiTest.show(inputs: () -> List<Dp>) {
        val host = SnackbarHostState()
        setContent {
            MaterialTheme {
                SnackbarAboveInput(host) {
                    inputs().forEachIndexed { i, height ->
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height).keepSnackbarsAbove().testTag("input$i"))
                    }
                    LaunchedEffect(Unit) { host.showSnackbar(message, duration = SnackbarDuration.Indefinite) }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.snackbar(): DpRect = onNodeWithText(message).getBoundsInRoot()

    private fun assertJustAbove(top: Dp, snackbar: DpRect) =
        assertTrue(snackbar.bottom <= top && top - snackbar.bottom < snackbarHeight, "snackbar bottom ${snackbar.bottom} just above $top")

    @Test
    fun aSnackbarSitsJustAboveAMarkedInput() = runComposeUiTest {
        show { listOf(80.dp) }
        assertJustAbove(onNodeWithTag("input0").getBoundsInRoot().top, snackbar())
    }

    @Test
    fun withTwoMarkedInputsItClearsTheHigher() = runComposeUiTest {
        show { listOf(80.dp, 200.dp) }
        assertJustAbove(onNodeWithTag("input1").getBoundsInRoot().top, snackbar())
    }

    @Test
    fun whenTheInputGoesItDropsToTheBottom() = runComposeUiTest {
        var inputShown by mutableStateOf(true)
        show { if (inputShown) listOf(200.dp) else emptyList() }
        inputShown = false
        waitForIdle()
        val root = onRoot().getBoundsInRoot()
        assertTrue(root.bottom - snackbar().bottom < snackbarHeight, "snackbar bottom ${snackbar().bottom} at the bottom ${root.bottom}")
    }

    @Test
    fun withNoInputItSitsAtTheBottom() = runComposeUiTest {
        show { emptyList() }
        val root = onRoot().getBoundsInRoot()
        assertTrue(root.bottom - snackbar().bottom < snackbarHeight, "snackbar bottom ${snackbar().bottom} at the bottom ${root.bottom}")
    }
}
